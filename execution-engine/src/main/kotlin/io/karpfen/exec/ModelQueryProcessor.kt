/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.io.karpfen.exec

import io.karpfen.dsl.instance.*
import io.karpfen.dsl.meta.AssociationType
import io.karpfen.dsl.meta.ClassTypeProperty
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.meta.SimplePropertyType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

/**
 * Reads and writes the model of one engine and publishes its changes to the registered [ModelChangePublisher]s.
 *
 * The processor is safe for several threads, which the feature TrueParallel needs: the branches of a split state
 * machine then run their action blocks at the same time on threads of their own. The objects of the model come from
 * dsl-tools and hold plain mutable lists, and a path or a JSON text reads through many objects, so one read-write lock
 * guards the whole model rather than a lock per object:
 * - every public read (paths, lookups, conversions to Python and JSON) holds the read lock, so reads of several
 *   threads run side by side, and a list it returns is a copy;
 * - every public write holds the write lock for the one operation, so a single write such as one `SET` or one
 *   `DROPOBJ` is atomic, while the reads and the Python code that compute its value run outside the lock.
 * Each public operation takes its lock and calls a private function of the same name with the suffix `Locked`, which
 * does the work and expects the lock to be held. A write called while the same thread holds only the read lock gives
 * that lock up for the write, as [kotlin.concurrent.write] does, so it cannot deadlock.
 *
 * Notification batches belong to the thread that opened them ([beginBatch]), so each action block publishes its own
 * changes once when it ends, whatever other branches do meanwhile. A write outside any batch publishes at once.
 * [commitBatch] publishes under the read lock and a publication lock, writing each object as JSON at that moment: two
 * commits never interleave, every publisher sees the publications of one commit in a row, and the last published
 * value of an object is always its latest one. The model lock is always taken before the publication lock.
 */
class ModelQueryProcessor(val metamodel: Metamodel, val model: Model) {

    companion object {
        /** Reserved leading path segment that refers to the event currently in scope. */
        const val EVENT_ROOT = "event"

        /** Builds a resolution scope containing only the event in scope (or empty when none). */
        fun eventScope(eventObj: DataObject?): Map<String, Any?> =
            if (eventObj != null) mapOf(EVENT_ROOT to eventObj) else emptyMap()
    }

    /**
     * Index of every object currently in the model, keyed by id. Maintained as objects are created
     * ([registerObjectTree]) and removed ([dropObject]) so that lookups, the `has`-containment
     * invariant, and inbound-reference scrubbing stay correct while the engine mutates the model.
     */
    private val objectIndex: MutableMap<String, DataObject> =
        Model.collectAllObjects(model.objects)
            .filter { it.id.isNotEmpty() }
            .associateBy { it.id }
            .toMutableMap()

    /** Monotonic counter backing [generateUniqueId]. */
    private var idCounter = 0

    /** Registered listeners that are notified whenever a DataObject changes; copy-on-write, registered at any time. */
    private val changePublishers = CopyOnWriteArrayList<ModelChangePublisher>()

    /** Registered listeners that learn which objects enter and leave the model; copy-on-write. */
    private val structureListeners = CopyOnWriteArrayList<ModelStructureListener>()

    /** Guards the objects of the model, the [objectIndex] and the [idCounter]. */
    private val modelLock = ReentrantReadWriteLock()

    /** Lets one commit at a time publish, so that the publications of two commits never interleave. */
    private val publicationLock = ReentrantLock()

    /** The notification batch the calling thread has open, or null outside a batch. */
    private val openBatch = ThreadLocal<NotificationBatch?>()

    /**
     * The changes of one batch: the changed objects, deduplicated so each fires exactly once, and the deleted ids,
     * fired after the change notifications on commit. Only the thread that opened the batch touches it.
     */
    private class NotificationBatch {
        val changed = LinkedHashSet<DataObject>()
        val deleted = LinkedHashSet<String>()
    }

    fun addChangePublisher(publisher: ModelChangePublisher) {
        changePublishers.add(publisher)
    }

    fun removeChangePublisher(publisher: ModelChangePublisher) {
        changePublishers.remove(publisher)
    }

    /**
     * Registers [listener] to learn about every object that enters the model through [registerObjectTree] and every
     * object that leaves it through [dropObject]. It is called inside the write operation, with the write lock held.
     */
    fun addStructureListener(listener: ModelStructureListener) {
        structureListeners.add(listener)
    }

    /**
     * Runs [read] while holding the read lock of the model, so that no write of another thread happens during it. For
     * readers that walk the model objects directly, such as a snapshot of the whole model.
     */
    fun <T> readModel(read: () -> T): T = modelLock.read(read)

    /**
     * Runs [write] while holding the write lock of the model, for code that changes model objects directly instead of
     * through the write operations of this processor. It publishes nothing by itself.
     */
    fun <T> writeModel(write: () -> T): T = modelLock.write(write)

    /**
     * Begin a notification batch of the calling thread. Notifications are deferred until [commitBatch] on the same
     * thread; a batch that is already open stays open.
     */
    fun beginBatch() {
        if (openBatch.get() == null) openBatch.set(NotificationBatch())
    }

    /**
     * Commits the batch of the calling thread and closes it: publishes one change per changed object that was not
     * deleted in the batch, then one deletion per deleted id. Without an open batch it publishes nothing.
     *
     * Each change and each deletion is published on its own, so one that fails never keeps the others of the batch
     * from being published. A changed object that cannot be written as JSON, because an object is reachable from
     * itself through its relations, is reported through [ModelChangePublisher.onObjectUnwritable] in place of its
     * change. Once everything was published, the first failure is thrown with the later ones attached as suppressed
     * exceptions.
     */
    fun commitBatch() {
        val batch = openBatch.get() ?: return
        openBatch.remove()
        val changed = batch.changed.toList()
        val deleted = batch.deleted.toSet()
        if (changed.isEmpty() && deleted.isEmpty()) return
        val failures = mutableListOf<Exception>()
        modelLock.read {
            publicationLock.withLock {
                for (obj in changed) {
                    if (obj.id !in deleted) publishIsolated(failures) { fireNotification(obj) }
                }
                for (id in deleted) publishIsolated(failures) { fireDeletion(id) }
            }
        }
        val first = failures.firstOrNull() ?: return
        failures.drop(1).forEach(first::addSuppressed)
        throw first
    }

    /** Runs one [publication] of a commit and adds its exception to [failures] instead of ending the commit. */
    private inline fun publishIsolated(failures: MutableList<Exception>, publication: () -> Unit) {
        try {
            publication()
        } catch (e: Exception) {
            failures.add(e)
        }
    }

    /**
     * Records the change of [obj] in the batch of the calling thread, or publishes it at once outside a batch. A write
     * operation calls it while holding the write lock, so the publication lock is taken after the model lock, in the
     * same order as in [commitBatch].
     */
    private fun notifyChange(obj: DataObject) {
        val batch = openBatch.get()
        if (batch != null) {
            batch.changed.add(obj)
            return
        }
        publicationLock.withLock { fireNotification(obj) }
    }

    /** Records the deletion of [objectId] like [notifyChange] records a change. */
    private fun notifyDeletion(objectId: String) {
        val batch = openBatch.get()
        if (batch != null) {
            batch.deleted.add(objectId)
            return
        }
        publicationLock.withLock { fireDeletion(objectId) }
    }

    /**
     * Publishes the change of [obj] with its JSON to every publisher. When [obj] cannot be written, every publisher
     * learns through [ModelChangePublisher.onObjectUnwritable] that the change happened, and the writer's exception is
     * rethrown.
     */
    private fun fireNotification(obj: DataObject) {
        if (changePublishers.isEmpty()) return
        val json = try {
            dataObjectToJson(obj)
        } catch (e: Exception) {
            for (publisher in changePublishers) {
                publisher.onObjectUnwritable(obj.id)
            }
            throw e
        }
        for (publisher in changePublishers) {
            publisher.onObjectChanged(obj.id, json)
        }
    }

    private fun fireDeletion(objectId: String) {
        for (publisher in changePublishers) {
            publisher.onObjectDeleted(objectId)
        }
    }

    /**
     * Appends a value to a list property on a [DataObject] identified by [objectId].
     * Notifies all registered change publishers after the update.
     *
     * @param objectId  The id of the DataObject to update.
     * @param key       The key of the list property to append to.
     * @param value     The value to append.
     */
    fun appendToList(objectId: String, key: String, value: Any) =
        modelLock.write { appendToListLocked(objectId, key, value) }

    private fun appendToListLocked(objectId: String, key: String, value: Any) {
        val obj = getDataObjectById(objectId)
        var prop = obj.properties.firstOrNull { it.key == key }

        // If the property does not exist on the instance yet, check the metamodel
        // and lazily create an empty SimpleListPropertyObject.
        if (prop == null) {
            val metaProp = obj.ofType.simpleProperties.firstOrNull { it.key == key }
            if (metaProp != null && metaProp.isList) {
                val newListProp = io.karpfen.dsl.instance.SimpleListPropertyObject(metaProp, key, mutableListOf())
                obj.properties.add(newListProp)
                prop = newListProp
            }
        }

        if (prop != null && prop.isList()) {
            val listProp = prop as io.karpfen.dsl.instance.SimpleListPropertyObject
            listProp.appendValue(value)
            notifyChange(obj)
        } else {
            throw IllegalArgumentException(
                "Property '$key' on object '$objectId' is not a list property"
            )
        }
    }

    /**
     * Infers the metamodel return-type string for the value at [path] relative to [contextObj].
     * This is used to parse action right-side EVAL/MACRO results into the correct type.
     *
     * Returns one of: "number", "boolean", "string", reference("TypeName"), list("TypeName"),
     * or a class-type name for embedded objects.
     */
    fun inferReturnType(contextObj: DataObject, path: String): String =
        modelLock.read { inferReturnTypeLocked(contextObj, path) }

    private fun inferReturnTypeLocked(contextObj: DataObject, path: String): String {
        val segments = path.split("->").map { it.trim() }
        var currentObj = contextObj
        var currentType: io.karpfen.dsl.meta.ClassType? = contextObj.ofType

        for (i in segments.indices) {
            val seg = segments[i]
            val isLast = i == segments.lastIndex

            // Check relations first
            val relProp = currentObj.relations.firstOrNull { it.key == seg }
            if (relProp != null) {
                val targetType = relProp.getPropertyType().reference.classTypeName
                if (isLast) {
                    return if (relProp.isList()) """list("$targetType")"""
                    else {
                        val assoc = relProp.getPropertyType().associationType
                        if (assoc == io.karpfen.dsl.meta.AssociationType.LINK) """reference("$targetType")"""
                        else targetType
                    }
                } else {
                    currentObj = currentObj.getRel(seg).first()
                    currentType = currentObj.ofType
                    continue
                }
            }

            // Check simple properties
            val simpleProp = currentObj.properties.firstOrNull { it.key == seg }
            if (simpleProp != null && isLast) {
                val vt = simpleProp.getValueType()
                return if (simpleProp.isList()) {
                    when (vt) {
                        io.karpfen.dsl.meta.SimplePropertyType.NUMBER -> """list("number")"""
                        io.karpfen.dsl.meta.SimplePropertyType.BOOLEAN -> """list("boolean")"""
                        io.karpfen.dsl.meta.SimplePropertyType.STRING -> """list("string")"""
                    }
                } else {
                    when (vt) {
                        io.karpfen.dsl.meta.SimplePropertyType.NUMBER -> "number"
                        io.karpfen.dsl.meta.SimplePropertyType.BOOLEAN -> "boolean"
                        io.karpfen.dsl.meta.SimplePropertyType.STRING -> "string"
                    }
                }
            }

            throw IllegalArgumentException(
                "Property or relation '$seg' not found on object '${currentObj.id}' of type '${currentObj.ofType.name}'"
            )
        }
        return currentObj.ofType.name
    }

    // ---- Write operations (with observer notification) --------------------

    /**
     * Updates the simple properties of a [DataObject] identified by [objectId] with the
     * provided [props] map (key → typed value) and notifies all registered change publishers.
     */
    fun updateProperties(objectId: String, props: Map<String, Any>) =
        modelLock.write { updatePropertiesLocked(objectId, props) }

    private fun updatePropertiesLocked(objectId: String, props: Map<String, Any>) {
        //println("property update --> " + objectId + " ---> " + props.toString())
        val obj = getDataObjectById(objectId)
        obj.assignProps(props)
        notifyChange(obj)
    }

    /**
     * Updates the relations of a [DataObject] identified by [objectId] with the provided
     * [rels] map and notifies all registered change publishers.
     */
    fun updateRelations(objectId: String, rels: Map<String, Any>) =
        modelLock.write { updateRelationsLocked(objectId, rels) }

    private fun updateRelationsLocked(objectId: String, rels: Map<String, Any>) {
        val obj = getDataObjectById(objectId)
        obj.assignRels(rels)
        notifyChange(obj)
    }

    /**
     * Updates a single simple property on a [DataObject] and notifies change publishers.
     */
    fun updateProperty(objectId: String, propertyKey: String, value: Any) {
        updateProperties(objectId, mapOf(propertyKey to value))
    }

    /**
     * Updates a single relation on a [DataObject] and notifies change publishers.
     */
    fun updateRelation(objectId: String, relationKey: String, value: Any) {
        updateRelations(objectId, mapOf(relationKey to value))
    }

    // ---- Object lifecycle & relation mutation (metamodel + containment validated) -----------

    /** Generates an id that is not currently used by any object in the model. */
    fun generateUniqueId(typeName: String): String = modelLock.write { generateUniqueIdLocked(typeName) }

    private fun generateUniqueIdLocked(typeName: String): String {
        var id: String
        do {
            id = "gen_${typeName}_${idCounter++}"
        } while (objectIndex.containsKey(id))
        return id
    }

    /** True when an object with [id] is currently part of the model. */
    fun containsObject(id: String): Boolean = modelLock.read { objectIndex.containsKey(id) }

    /**
     * Registers a newly-created object and its embedded (`has`) subtree in the object index. Every
     * object must already carry a non-empty, model-unique id (macro-built objects get one at
     * construction time). Throws if any id already exists in the model. Every [ModelStructureListener] then learns the
     * registered objects, the root first.
     */
    fun registerObjectTree(root: DataObject) = modelLock.write { registerObjectTreeLocked(root) }

    private fun registerObjectTreeLocked(root: DataObject) {
        val subtree = Model.collectAllObjects(mutableListOf(root))
        for (obj in subtree) {
            require(obj.id.isNotEmpty()) {
                "Cannot register an object of type '${obj.ofType.name}' with an empty id"
            }
            val existing = objectIndex[obj.id]
            require(existing == null || existing === obj) {
                "Object id '${obj.id}' already exists in the model; cannot introduce a new object with the same id"
            }
        }
        for (obj in subtree) objectIndex[obj.id] = obj
        val added = subtree.toList()
        structureListeners.forEach { it.objectsAdded(added) }
    }

    private fun validateNewObject(target: DataObject, relationKey: String) {
        require(!objectIndex.containsKey(target.id)) {
            "Relation '$relationKey' is a 'has' relation and requires a NEW object, but '${target.id}' " +
                "already exists in the model. Reference existing objects through a 'knows' relation instead."
        }
    }

    private fun validateExistingObject(target: DataObject, relationKey: String) {
        require(objectIndex[target.id] === target) {
            "Relation '$relationKey' is a 'knows' relation and requires an EXISTING model object, but " +
                "'${target.id}' is not part of the model. Introduce new objects through a 'has' relation instead."
        }
    }

    /**
     * Overwrites a single scalar property. Validates that [key] is a non-list simple property of the
     * owner's type (relations must use SETOBJ; list properties must use SETLIST/APPEND).
     */
    fun setScalar(ownerId: String, key: String, value: Any) = modelLock.write { setScalarLocked(ownerId, key, value) }

    private fun setScalarLocked(ownerId: String, key: String, value: Any) {
        val owner = getDataObjectById(ownerId)
        val propDef = owner.ofType.simplePropsAsMap()[key]
            ?: throw IllegalArgumentException(
                "No scalar property '$key' on type '${owner.ofType.name}' (use SETOBJ for relations)"
            )
        require(!propDef.isList) { "Property '$key' is a list; use SETLIST or APPEND instead of SET" }
        updateProperty(ownerId, key, value)
    }

    /** Overwrites a whole simple list property, lazily creating it if absent. */
    fun setSimpleList(ownerId: String, key: String, values: List<Any>) =
        modelLock.write { setSimpleListLocked(ownerId, key, values) }

    private fun setSimpleListLocked(ownerId: String, key: String, values: List<Any>) {
        val owner = getDataObjectById(ownerId)
        val propDef = owner.ofType.simplePropsAsMap()[key]
            ?: throw IllegalArgumentException("No simple property '$key' on type '${owner.ofType.name}'")
        require(propDef.isList) { "Property '$key' is not a list; use SET instead of SETLIST" }
        val prop = (owner.properties.firstOrNull { it.key == key } as? SimpleListPropertyObject)
            ?: SimpleListPropertyObject(propDef, key, mutableListOf()).also { owner.properties.add(it) }
        prop.setValues(values)
        notifyChange(owner)
    }

    /** Clears a simple list property (sets it to empty). */
    fun clearSimpleList(ownerId: String, key: String) = modelLock.write { clearSimpleListLocked(ownerId, key) }

    private fun clearSimpleListLocked(ownerId: String, key: String) {
        val owner = getDataObjectById(ownerId)
        val propDef = owner.ofType.simplePropsAsMap()[key]
            ?: throw IllegalArgumentException("No simple property '$key' on type '${owner.ofType.name}'")
        require(propDef.isList) { "Property '$key' is not a list; DROPLIST only applies to list properties" }
        (owner.properties.firstOrNull { it.key == key } as? SimpleListPropertyObject)?.clear()
        notifyChange(owner)
    }

    /**
     * Replaces the target of an atomic relation. For a `has` (EMBEDDED) relation the [target] must be a
     * new object: the previous occupant is dropped and the new object is embedded. For a `knows` (LINK)
     * relation the [target] must already exist in the model: only the reference is repointed.
     */
    fun setObjectRelation(ownerId: String, relationKey: String, target: DataObject) =
        modelLock.write { setObjectRelationLocked(ownerId, relationKey, target) }

    private fun setObjectRelationLocked(ownerId: String, relationKey: String, target: DataObject) {
        val owner = getDataObjectById(ownerId)
        val relDef = owner.ofType.objectPropsAsMap()[relationKey]
            ?: throw IllegalArgumentException("No object relation '$relationKey' on type '${owner.ofType.name}'")
        require(!relDef.isList) { "Relation '$relationKey' is a list; use APPENDOBJ/DROPOBJ instead of SETOBJ" }
        require(target.ofType.name == relDef.reference.classTypeName) {
            "Relation '$relationKey' expects type '${relDef.reference.classTypeName}' but got '${target.ofType.name}'"
        }
        val rel = owner.relations.firstOrNull { it.key == relationKey } as? ClassTypeAtomicPropertyObject
            ?: throw IllegalArgumentException("Relation '$relationKey' is not present on object '$ownerId'")

        if (relDef.associationType == AssociationType.EMBEDDED) {
            validateNewObject(target, relationKey)
            val old = rel.getValue()
            if (old != null) dropObject(old.id)
            registerObjectTree(target)
            rel.setValue(target)
        } else {
            validateExistingObject(target, relationKey)
            rel.setValue(target)
        }
        notifyChange(owner)
    }

    /**
     * Adds an object to a list relation. `has` (EMBEDDED) lists take new objects (embedded + registered);
     * `knows` (LINK) lists take existing objects (referenced).
     */
    fun appendObjectRelation(ownerId: String, relationKey: String, target: DataObject) =
        modelLock.write { appendObjectRelationLocked(ownerId, relationKey, target) }

    private fun appendObjectRelationLocked(ownerId: String, relationKey: String, target: DataObject) {
        val owner = getDataObjectById(ownerId)
        val relDef = owner.ofType.objectPropsAsMap()[relationKey]
            ?: throw IllegalArgumentException("No object relation '$relationKey' on type '${owner.ofType.name}'")
        require(relDef.isList) { "Relation '$relationKey' is not a list; use SETOBJ instead of APPENDOBJ" }
        require(target.ofType.name == relDef.reference.classTypeName) {
            "Relation '$relationKey' expects type '${relDef.reference.classTypeName}' but got '${target.ofType.name}'"
        }
        val rel = (owner.relations.firstOrNull { it.key == relationKey } as? ClassTypeListPropertyObject)
            ?: ClassTypeListPropertyObject(relDef, relationKey, mutableListOf()).also { owner.relations.add(it) }

        if (relDef.associationType == AssociationType.EMBEDDED) {
            validateNewObject(target, relationKey)
            registerObjectTree(target)
        } else {
            validateExistingObject(target, relationKey)
        }
        rel.appendValue(target)
        notifyChange(owner)
    }

    /**
     * Changes the object [existing] of the model in place with the simple properties [props] and the relations [rels],
     * as a dictionary with the `__id__` of an existing object that an `EVAL` or a macro returns asks for.
     *
     * Each relation in [rels] takes its new objects, a [DataObject] for a single relation and a list for a list
     * relation, in the order given. For a `has` relation, every object the relation held before and does not hold any
     * more leaves the model like through [dropObject], with everything it holds: its `knows` references are scrubbed,
     * its deletion is published and every [ModelStructureListener] learns it. Every object the relation now holds and
     * the model does not know yet is registered like through [registerObjectTree], with everything it holds. An object
     * the model knows may only stay in the relation that already holds it. For a `knows` relation every object must be
     * in the model. Relations of [rels] that the type of [existing] does not have are ignored. Finally the change of
     * [existing] is published.
     *
     * @throws IllegalArgumentException when an object has the wrong type, when a `has` relation is given an object the
     *   model holds elsewhere, or when a `knows` relation is given an object that is not in the model. Changes made to
     *   earlier relations of [rels] stay then.
     */
    fun updateObjectInPlace(existing: DataObject, props: Map<String, Any>, rels: Map<String, Any>) =
        modelLock.write { updateObjectInPlaceLocked(existing, props, rels) }

    private fun updateObjectInPlaceLocked(existing: DataObject, props: Map<String, Any>, rels: Map<String, Any>) {
        require(objectIndex[existing.id] === existing) { "Object '${existing.id}' is not part of the model" }
        existing.assignProps(props)
        val relDefs = existing.ofType.objectPropsAsMap()
        for ((key, value) in rels) {
            val relDef = relDefs[key] ?: continue
            @Suppress("UNCHECKED_CAST")
            val targets = if (relDef.isList) value as List<DataObject> else listOf(value as DataObject)
            for (target in targets) {
                require(target.ofType.name == relDef.reference.classTypeName) {
                    "Relation '$key' expects type '${relDef.reference.classTypeName}' but got '${target.ofType.name}'"
                }
            }
            if (relDef.associationType == AssociationType.EMBEDDED) {
                replaceHeldObjects(existing, relDef, key, targets)
            } else {
                targets.forEach { validateExistingObject(it, key) }
                assignRelation(existing, relDef, key, targets)
            }
        }
        notifyChange(existing)
    }

    /**
     * Lets the `has` relation [key] of [owner] hold [targets]: drops the objects it held and no longer holds, then
     * assigns the relation and registers the targets that are new to the model.
     */
    private fun replaceHeldObjects(owner: DataObject, relDef: ClassTypeProperty, key: String, targets: List<DataObject>) {
        val held = when (val rel = owner.relations.firstOrNull { it.key == key }) {
            is ClassTypeAtomicPropertyObject -> listOfNotNull(rel.getValue())
            is ClassTypeListPropertyObject -> rel.getValues()
            else -> emptyList()
        }
        for (target in targets) {
            val known = objectIndex[target.id] ?: continue
            require(known === target && held.any { it === target }) {
                "Relation '$key' is a 'has' relation and can only keep the objects it holds, but '${target.id}' " +
                    "is held elsewhere in the model"
            }
        }
        for (old in held) {
            if (targets.none { it === old } && objectIndex[old.id] === old) dropObjectLocked(old.id)
        }
        assignRelation(owner, relDef, key, targets)
        for (target in targets) {
            if (!objectIndex.containsKey(target.id)) registerObjectTreeLocked(target)
        }
    }

    /** Sets the relation [key] of [owner] to [targets], creating a list relation that the object does not have yet. */
    private fun assignRelation(owner: DataObject, relDef: ClassTypeProperty, key: String, targets: List<DataObject>) {
        if (relDef.isList) {
            val rel = (owner.relations.firstOrNull { it.key == key } as? ClassTypeListPropertyObject)
                ?: ClassTypeListPropertyObject(relDef, key, mutableListOf()).also { owner.relations.add(it) }
            rel.setValues(targets)
        } else {
            val rel = owner.relations.firstOrNull { it.key == key } as? ClassTypeAtomicPropertyObject
                ?: throw IllegalArgumentException("Relation '$key' is not present on object '${owner.id}'")
            rel.setValue(targets.single())
        }
    }

    /**
     * Removes an object and its embedded (`has`) subtree from the model, scrubbing every inbound `knows`
     * reference. Affected owner objects receive change notifications; every removed object id is reported
     * via [ModelChangePublisher.onObjectDeleted], and every [ModelStructureListener] learns the removed ids.
     */
    fun dropObject(objId: String) = modelLock.write { dropObjectLocked(objId) }

    private fun dropObjectLocked(objId: String) {
        val target = getDataObjectById(objId)
        val removedIds = Model.collectAllObjects(mutableListOf(target)).map { it.id }.toSet()
        val affectedOwners = LinkedHashSet<DataObject>()

        // 1. Detach from its `has` container (or the model roots if it is a root object).
        val container = findEmbeddedContainer(target)
        if (container != null) {
            val (ownerObj, rel) = container
            when (rel) {
                is ClassTypeAtomicPropertyObject -> rel.clear()
                is ClassTypeListPropertyObject -> rel.removeById(target.id)
            }
            affectedOwners.add(ownerObj)
        } else {
            model.objects.removeIf { it === target }
        }

        // 2. Scrub every inbound `knows` reference into the removed subtree.
        for (obj in objectIndex.values) {
            if (obj.id in removedIds) continue
            for (rel in obj.relations) {
                if (rel.getPropertyType().associationType != AssociationType.LINK) continue
                when (rel) {
                    is ClassTypeAtomicPropertyObject -> {
                        val v = rel.getValue()
                        if (v != null && v.id in removedIds) {
                            rel.clear()
                            affectedOwners.add(obj)
                        }
                    }
                    is ClassTypeListPropertyObject -> {
                        val sizeBefore = rel.getValueRefs().size
                        removedIds.forEach { rel.removeById(it) }
                        if (rel.getValueRefs().size != sizeBefore) affectedOwners.add(obj)
                    }
                }
            }
        }

        // 3. Unregister the removed subtree.
        for (id in removedIds) objectIndex.remove(id)

        // 4. Notify: surviving owners changed, removed ids deleted, structure listeners told what left.
        affectedOwners.forEach { if (it.id !in removedIds) notifyChange(it) }
        removedIds.forEach { notifyDeletion(it) }
        structureListeners.forEach { it.objectsRemoved(removedIds) }
    }

    /** Finds the single object whose `has` (EMBEDDED) relation currently contains [target], if any. */
    fun findEmbeddedContainer(target: DataObject): Pair<DataObject, ClassTypePropertyObject>? =
        modelLock.read { findEmbeddedContainerLocked(target) }

    private fun findEmbeddedContainerLocked(target: DataObject): Pair<DataObject, ClassTypePropertyObject>? {
        for (obj in objectIndex.values) {
            for (rel in obj.relations) {
                if (rel.getPropertyType().associationType != AssociationType.EMBEDDED) continue
                val contains = when (rel) {
                    is ClassTypeAtomicPropertyObject -> rel.getValue() === target
                    is ClassTypeListPropertyObject -> rel.getValues().any { it === target }
                    else -> false
                }
                if (contains) return obj to rel
            }
        }
        return null
    }

    // ---- JSON serialisation -----------------------------------------------

    /**
     * Serialises a [DataObject] to a JSON string for WebSocket push notifications; [ModelJsonWriter.write] does the
     * writing, so pushes and model snapshots share one format.
     */
    fun dataObjectToJson(obj: DataObject): String = modelLock.read { ModelJsonWriter.write(obj) }

    /**
     * Finds a DataObject by its unique id across all objects (including nested/embedded).
     */
    fun getDataObjectById(id: String): DataObject = modelLock.read { getDataObjectByIdLocked(id) }

    private fun getDataObjectByIdLocked(id: String): DataObject {
        return objectIndex[id]
            ?: throw IllegalArgumentException("No DataObject found with id '$id'")
    }

    /**
     * Starting from a DataObject identified by [startObjectId], traverse the [argumentPath]
     * (e.g. ["boundingBox", "position", "x"]) to resolve the final value.
     *
     * At each step the segment is first looked up as a relation (object property).
     * If found and it's not the last segment, navigation continues into that object.
     * If it's the last segment and it resolves to a single DataObject, that object is returned.
     * Otherwise, the segment is looked up as a simple property.
     *
     * @return The resolved value: a DataObject, a List<DataObject>, a primitive value, or a List of primitives.
     */
    fun getValueOfThing(startObjectId: String, argumentPath: List<String>): Any? =
        modelLock.read { getValueOfThingLocked(startObjectId, argumentPath) }

    private fun getValueOfThingLocked(startObjectId: String, argumentPath: List<String>): Any? {
        if (argumentPath.isEmpty()) return getDataObjectById(startObjectId)

        var currentObj = getDataObjectById(startObjectId)

        for (i in argumentPath.indices) {
            val segment = argumentPath[i]
            val isLast = i == argumentPath.lastIndex

            // Try relation first
            val relResult = currentObj.getRel(segment)
            if (relResult.isNotEmpty()) {
                if (isLast) {
                    // Return single object or list depending on metamodel definition
                    val relProp = currentObj.relations.firstOrNull { it.key == segment }
                    return if (relProp != null && relProp.isList()) {
                        relResult
                    } else {
                        relResult.first()
                    }
                } else {
                    // Navigate into the first (or only) object
                    currentObj = relResult.first()
                    continue
                }
            }

            // Try simple property
            val simpleProp = currentObj.properties.firstOrNull { it.key == segment }
            if (simpleProp != null) {
                if (!isLast) {
                    throw IllegalArgumentException(
                        "Cannot navigate further through simple property '$segment' — it is not a DataObject"
                    )
                }
                return if (simpleProp.isList()) {
                    currentObj.getProp(segment).toList()
                } else {
                    currentObj.getProp(segment).firstOrNull()
                }
            }

            throw IllegalArgumentException(
                "Property or relation '$segment' not found on object '${currentObj.id}' of type '${currentObj.ofType.name}'"
            )
        }

        return currentObj
    }

    /**
     * Resolves a path string, honouring the reserved [EVENT_ROOT] prefix.
     *
     * A path that starts with `event` is resolved against [eventObj] (the payload of the event in
     * scope) rather than [contextObj]; `event` on its own returns the payload object itself. Any other
     * path resolves against [contextObj] exactly like [resolvePathFromObject].
     *
     * @throws IllegalArgumentException if the path is rooted at `event` but no event is in scope, or if
     *         a segment cannot be resolved.
     */
    fun resolvePathInScope(contextObj: DataObject, scope: Map<String, Any?>, path: String): Any? =
        modelLock.read { resolvePathInScopeLocked(contextObj, scope, path) }

    private fun resolvePathInScopeLocked(contextObj: DataObject, scope: Map<String, Any?>, path: String): Any? {
        val segments = path.split("->").map { it.trim() }
        val root = segments.firstOrNull()
        if (root != null && scope.containsKey(root)) {
            val bound = scope[root]
            val rest = segments.drop(1)
            if (rest.isEmpty()) return bound
            return when (bound) {
                is DataObject -> resolvePathFromObject(bound, rest.joinToString("->"))
                null -> throw IllegalArgumentException("Binding '$root' is null; cannot resolve path '$path'")
                else -> throw IllegalArgumentException("Binding '$root' is not an object; cannot navigate path '$path'")
            }
        }
        return resolvePathFromObject(contextObj, path)
    }

    /** Like [resolvePathInScope] but returns null instead of throwing when the path cannot be resolved. */
    fun tryResolvePathInScope(contextObj: DataObject, scope: Map<String, Any?>, path: String): Any? =
        try {
            resolvePathInScope(contextObj, scope, path)
        } catch (_: Exception) {
            null
        }

    /** Event-only convenience over [resolvePathInScope]; honours the reserved `event` root. */
    fun resolvePathWithEvent(contextObj: DataObject, eventObj: DataObject?, path: String): Any? =
        resolvePathInScope(contextObj, eventScope(eventObj), path)

    /**
     * Like [resolvePathWithEvent] but returns null instead of throwing when the path cannot be
     * resolved. Used to test whether an access path is currently available (e.g. for IF IN SCOPE).
     */
    fun tryResolvePathWithEvent(contextObj: DataObject, eventObj: DataObject?, path: String): Any? =
        tryResolvePathInScope(contextObj, eventScope(eventObj), path)

    /**
     * Resolves a path string (e.g. "boundingBox->position->x") relative to a given context DataObject.
     */
    fun resolvePathFromObject(contextObj: DataObject, path: String): Any? =
        modelLock.read { resolvePathFromObjectLocked(contextObj, path) }

    private fun resolvePathFromObjectLocked(contextObj: DataObject, path: String): Any? {
        val segments = path.split("->").map { it.trim() }
        if (segments.isEmpty()) return contextObj

        var currentObj = contextObj
        for (i in segments.indices) {
            val segment = segments[i]
            val isLast = i == segments.lastIndex

            val relResult = currentObj.getRel(segment)
            if (relResult.isNotEmpty()) {
                if (isLast) {
                    val relProp = currentObj.relations.firstOrNull { it.key == segment }
                    return if (relProp != null && relProp.isList()) relResult else relResult.first()
                } else {
                    currentObj = relResult.first()
                    continue
                }
            }

            val simpleProp = currentObj.properties.firstOrNull { it.key == segment }
            if (simpleProp != null) {
                if (!isLast) {
                    throw IllegalArgumentException(
                        "Cannot navigate further through simple property '$segment'"
                    )
                }
                return if (simpleProp.isList()) currentObj.getProp(segment).toList() else currentObj.getProp(segment).firstOrNull()
            }

            throw IllegalArgumentException(
                "Property or relation '$segment' not found on object '${currentObj.id}' of type '${currentObj.ofType.name}'"
            )
        }
        return currentObj
    }

    /**
     * Serializes a DataObject to a Python dict string representation, recursively including
     * embedded objects and simple properties.
     */
    fun dataObjectToPythonDict(obj: DataObject): String = modelLock.read { dataObjectToPythonDictLocked(obj) }

    private fun dataObjectToPythonDictLocked(obj: DataObject): String {
        val entries = mutableListOf<String>()

        // Add the object id
        entries.add("\"__id__\": \"${obj.id}\"")
        entries.add("\"__type__\": \"${obj.ofType.name}\"")

        // Simple properties
        for (prop in obj.properties) {
            if (prop.isList()) {
                val listProp = prop as SimpleListPropertyObject
                val values = listProp.getValues().joinToString(", ") { valueToPythonLiteral(it, prop.getValueType()) }
                entries.add("\"${prop.key}\": [$values]")
            } else {
                val atomicProp = prop as SimpleAtomicPropertyObject
                entries.add("\"${prop.key}\": ${valueToPythonLiteral(atomicProp.getValue(), prop.getValueType())}")
            }
        }

        // Embedded relations (has)
        for (rel in obj.relations) {
            if (rel.getPropertyType().associationType == AssociationType.EMBEDDED) {
                if (rel.isList()) {
                    val listRel = rel as ClassTypeListPropertyObject
                    val values = listRel.getValues().joinToString(", ") { dataObjectToPythonDict(it) }
                    entries.add("\"${rel.key}\": [$values]")
                } else {
                    val atomicRel = rel as ClassTypeAtomicPropertyObject
                    val value = atomicRel.getValue()
                    if (value != null) {
                        entries.add("\"${rel.key}\": ${dataObjectToPythonDict(value)}")
                    } else {
                        entries.add("\"${rel.key}\": None")
                    }
                }
            } else {
                // LINK relations: serialize as reference id(s)
                if (rel.isList()) {
                    val listRel = rel as ClassTypeListPropertyObject
                    val values = listRel.getValues().joinToString(", ") { dataObjectToPythonDict(it) }
                    entries.add("\"${rel.key}\": [$values]")
                } else {
                    val atomicRel = rel as ClassTypeAtomicPropertyObject
                    val value = atomicRel.getValue()
                    if (value != null) {
                        entries.add("\"${rel.key}\": ${dataObjectToPythonDict(value)}")
                    } else {
                        entries.add("\"${rel.key}\": None")
                    }
                }
            }
        }

        return "{${entries.joinToString(", ")}}"
    }

    /**
     * Converts a primitive value to its Python literal representation.
     */
    fun valueToPythonLiteral(value: Any, type: SimplePropertyType): String {
        return when (type) {
            SimplePropertyType.STRING -> "\"$value\""
            SimplePropertyType.NUMBER -> {
                val d = value as Double
                if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
            }
            SimplePropertyType.BOOLEAN -> if (value as Boolean) "True" else "False"
        }
    }

    /**
     * Converts an arbitrary resolved value to a Python literal string suitable for embedding in code.
     */
    fun anyToPythonLiteral(value: Any?): String = modelLock.read { anyToPythonLiteralLocked(value) }

    private fun anyToPythonLiteralLocked(value: Any?): String {
        return when (value) {
            null -> "None"
            is DataObject -> dataObjectToPythonDict(value)
            is List<*> -> {
                val elements = value.map { anyToPythonLiteral(it) }
                "[${elements.joinToString(", ")}]"
            }
            is String -> "\"$value\""
            is Boolean -> if (value) "True" else "False"
            is Double -> {
                if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            }
            is Number -> value.toString()
            else -> "\"$value\""
        }
    }

}