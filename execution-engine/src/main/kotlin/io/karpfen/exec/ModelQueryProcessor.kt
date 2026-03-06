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

import dsl.textual.KmodelDSLConverter
import instance.*
import meta.AssociationType
import meta.Metamodel
import meta.SimplePropertyType

class ModelQueryProcessor(val metamodel: Metamodel, val model: Model) {

    private val allObjects: Set<DataObject> by lazy {
        KmodelDSLConverter.collectAllObjects(model.objects)
    }

    /** Registered listeners that are notified whenever a DataObject changes. */
    private val changePublishers: MutableList<ModelChangePublisher> = mutableListOf()

    fun addChangePublisher(publisher: ModelChangePublisher) {
        changePublishers.add(publisher)
    }

    fun removeChangePublisher(publisher: ModelChangePublisher) {
        changePublishers.remove(publisher)
    }

    private fun notifyChange(obj: DataObject) {
        if (changePublishers.isEmpty()) return
        val json = dataObjectToJson(obj)
        for (publisher in changePublishers) {
            publisher.onObjectChanged(obj.id, json)
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
    fun appendToList(objectId: String, key: String, value: Any) {
        val obj = getDataObjectById(objectId)
        val prop = obj.properties.firstOrNull { it.key == key }
        if (prop != null && prop.isList()) {
            val listProp = prop as instance.SimpleListPropertyObject
            val current = listProp.getValues().toMutableList()
            current.add(value)
            listProp.setValues(current)
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
    fun inferReturnType(contextObj: DataObject, path: String): String {
        val segments = path.split("->").map { it.trim() }
        var currentObj = contextObj
        var currentType: meta.ClassType? = contextObj.ofType

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
                        if (assoc == meta.AssociationType.LINK) """reference("$targetType")"""
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
                        meta.SimplePropertyType.NUMBER -> """list("number")"""
                        meta.SimplePropertyType.BOOLEAN -> """list("boolean")"""
                        meta.SimplePropertyType.STRING -> """list("string")"""
                    }
                } else {
                    when (vt) {
                        meta.SimplePropertyType.NUMBER -> "number"
                        meta.SimplePropertyType.BOOLEAN -> "boolean"
                        meta.SimplePropertyType.STRING -> "string"
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
    fun updateProperties(objectId: String, props: Map<String, Any>) {
        val obj = getDataObjectById(objectId)
        obj.assignProps(props)
        notifyChange(obj)
    }

    /**
     * Updates the relations of a [DataObject] identified by [objectId] with the provided
     * [rels] map and notifies all registered change publishers.
     */
    fun updateRelations(objectId: String, rels: Map<String, Any>) {
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

    // ---- JSON serialisation -----------------------------------------------

    /**
     * Serialises a [DataObject] to a JSON string (using the same structure as [dataObjectToPythonDict]
     * but with proper JSON syntax). Suitable for WebSocket push notifications.
     */
    fun dataObjectToJson(obj: DataObject): String {
        return buildJsonObject(obj)
    }

    private fun buildJsonObject(obj: DataObject): String {
        val entries = mutableListOf<String>()
        entries.add("\"__id__\": \"${obj.id}\"")
        entries.add("\"__type__\": \"${obj.ofType.name}\"")

        for (prop in obj.properties) {
            if (prop.isList()) {
                val listProp = prop as instance.SimpleListPropertyObject
                val values = listProp.getValues().joinToString(", ") { valueToJsonLiteral(it, prop.getValueType()) }
                entries.add("\"${prop.key}\": [$values]")
            } else {
                val atomicProp = prop as instance.SimpleAtomicPropertyObject
                entries.add("\"${prop.key}\": ${valueToJsonLiteral(atomicProp.getValue(), prop.getValueType())}")
            }
        }

        for (rel in obj.relations) {
            if (rel.isList()) {
                val listRel = rel as instance.ClassTypeListPropertyObject
                // getValueRefs() is used to safely check for null obj before calling getValues()
                val values = listRel.getValueRefs().joinToString(", ") { ref ->
                    if (ref.obj != null) buildJsonObject(ref.obj!!) else "null"
                }
                entries.add("\"${rel.key}\": [$values]")
            } else {
                val atomicRel = rel as instance.ClassTypeAtomicPropertyObject
                val value = atomicRel.getValue()
                if (value != null) {
                    entries.add("\"${rel.key}\": ${buildJsonObject(value)}")
                } else {
                    entries.add("\"${rel.key}\": null")
                }
            }
        }

        return "{${entries.joinToString(", ")}}"
    }

    private fun valueToJsonLiteral(value: Any, type: meta.SimplePropertyType): String {
        return when (type) {
            meta.SimplePropertyType.STRING -> "\"${value.toString().replace("\"", "\\\"")}\""
            meta.SimplePropertyType.NUMBER -> {
                val d = value as Double
                if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
            }
            meta.SimplePropertyType.BOOLEAN -> if (value as Boolean) "true" else "false"
        }
    }

    /**
     * Finds a DataObject by its unique id across all objects (including nested/embedded).
     */
    fun getDataObjectById(id: String): DataObject {
        return allObjects.firstOrNull { it.id == id }
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
    fun getValueOfThing(startObjectId: String, argumentPath: List<String>): Any? {
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
                    currentObj.getProp(segment)
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
     * Resolves a path string (e.g. "boundingBox->position->x") relative to a given context DataObject.
     */
    fun resolvePathFromObject(contextObj: DataObject, path: String): Any? {
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
                return if (simpleProp.isList()) currentObj.getProp(segment) else currentObj.getProp(segment).firstOrNull()
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
    fun dataObjectToPythonDict(obj: DataObject): String {
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
    fun anyToPythonLiteral(value: Any?): String {
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