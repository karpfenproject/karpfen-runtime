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

import io.karpfen.dsl.instance.DataObject
import io.karpfen.dsl.instance.Model

/**
 * Owns the instances of state machines of one engine: which instances exist, in which order they do their steps, and
 * when they join and leave.
 *
 * The rule of change: the order of instances, [instances], changes only on the engine thread, at two points. [start]
 * builds the instances of a start, and [applyChanges], which the engine calls at the end of every tick inside the tick
 * gate, carries out what the tick asked for. A stop of the engine changes nothing here: the instances and the requests
stay as they are until the engine continues. Everything else only asks, from whatever thread changes the model or runs
 * an action, and the requests are collected under [lock]:
 * - an object that enters the model ([objectsAdded]) and whose type behaves after a definition asks for an instance;
 * - an `ATTACH` clause ([attach]) names the definition of the object its rule created, in place of that one;
 * - an object that leaves the model ([objectsRemoved]) ends the instance of the object at once and cancels what was
 *   asked for it;
 * - `EXCHANGE` ([exchange]) ends the instance of the object at once and asks for an instance of the named definition at
 *   its place, or asks for a new one when the object has none.
 *
 * An ended instance does no further step from that moment (see [SMContext.end]). At the end of the tick [applyChanges]
 * takes every ended instance out of the order, puts the instance an exchange asked for at its place, and appends the
 * new instances in the order in which they were asked for. Every new instance starts in its initial state and does its
 * first step in the next tick. Requests for objects that are no longer in the model at that point are dropped. So the
 * engine thread walks a list that no other thread changes, while the branches of a split, with the feature
 * TrueParallel on, and the engine thread may ask at the same time.
 *
 * The model calls [objectsAdded] and [objectsRemoved] with its write lock held. This class takes its own [lock] inside
 * that one and never waits for the model while it holds its own, so the two locks are always taken in the same order.
 *
 * Other threads read [view], an immutable copy of the instances that changes with them: before the first start the
 * instances a start would build, then the instances in the order of the tick.
 *
 * @property definitions the state machines the engine knows.
 * @property factory builds the context of an instance.
 * @property model the model, read at [start] for the instances of a start.
 * @property modelQueryProcessor tells whether an object is still in the model when a request is carried out.
 * @property listener learns every instance that joins and leaves, on the engine thread inside the tick gate.
 */
class StateMachineLifecycle(
    private val definitions: StateMachineDefinitions,
    private val factory: StateMachineInstanceFactory,
    private val model: Model,
    private val modelQueryProcessor: ModelQueryProcessor,
    private val listener: StateMachineLifecycleListener
) : StateMachineAttachments, ModelStructureListener {

    /** Guards [live], [pendingAttachments] and [replacements]. */
    private val lock = Any()

    /** The instances in the order of the tick. Replaced only on the engine thread, read there without a lock. */
    @Volatile
    var instances: List<SMContext> = emptyList()
        private set

    /** The installed instances by the key of their object, ended ones included until [applyChanges]. */
    private val live = HashMap<String, SMContext>()

    /** New instances asked for, the key of the object to the name of the definition, in the order asked. */
    private val pendingAttachments = LinkedHashMap<String, String>()

    /** Instances asked for by `EXCHANGE` at the place of an ended instance, by the key of the object. */
    private val replacements = HashMap<String, String>()

    /** An immutable copy of the instances for other threads; see the class description. */
    @Volatile
    var view: List<InstanceSpec> = definitions.initialInstances(model.objects, skipMissingObjects = true)
        private set

    /**
     * Builds the instances over the model, in the order [StateMachineDefinitions.initialInstances] gives, and tells the
     * [listener] that each joined. Called once, on the engine thread inside the tick gate, before the first tick. An
     * attached state machine whose object is not in the model is an error that ends the engine thread.
     */
    fun start() {
        val built = definitions.initialInstances(model.objects).map { factory.build(it, this) }
        install(built)
        built.forEach { listener.joined(it, null) }
    }

    /**
     * Carries out the requests of the tick that ends; see the class description. Instances that leave are told to the
     * [listener] first, in the order of the tick, then the instances that joined, in their new order; an instance that
     * an exchange put at the place of another is told with the instance it replaces, both when that one leaves and when
     * it joins. The macro processor of every instance that leaves is closed. Called on the engine thread at the end of
     * every tick.
     */
    fun applyChanges() {
        val current = instances
        val (wanted, added) = synchronized(lock) {
            if (current.none { it.hasEnded } && pendingAttachments.isEmpty()) return
            val exchanged = HashMap(replacements)
            val attachments = LinkedHashMap(pendingAttachments)
            replacements.clear()
            pendingAttachments.clear()
            exchanged to attachments
        }

        val next = ArrayList<SMContext>(current.size + added.size)
        val left = ArrayList<SMContext>()
        val joined = ArrayList<SMContext>()
        val successors = HashMap<SMContext, SMContext>()
        val predecessors = HashMap<SMContext, SMContext>()
        for (ctx in current) {
            if (!ctx.hasEnded) {
                next += ctx
                continue
            }
            left += ctx
            val name = wanted[ctx.modelElementId] ?: continue
            build(ctx.modelElementId, name)?.let {
                next += it
                joined += it
                successors[ctx] = it
                predecessors[it] = ctx
            }
        }
        for ((objectId, name) in added) {
            if (next.any { it.modelElementId == objectId }) {
                System.err.println("[StateMachineLifecycle] '$objectId' has a state machine already; '$name' is not attached")
                continue
            }
            build(objectId, name)?.let { next += it; joined += it }
        }

        install(next)
        for (ctx in left) {
            closeQuietly(ctx)
            listener.left(ctx, successors[ctx])
        }
        joined.forEach { listener.joined(it, predecessors[it]) }
    }

    /** Closes the macro processors of every instance, when the engine stops. */
    fun close() {
        instances.forEach(::closeQuietly)
    }

    override fun requireAttachable(name: String, typeName: String) {
        definitions.requireAttachable(name, typeName)
    }

    override fun attach(objectId: String, name: String) {
        synchronized(lock) { pendingAttachments[objectId] = name }
    }

    override fun exchange(objectId: String, name: String) {
        synchronized(lock) {
            val ctx = live[objectId]
            if (objectId in pendingAttachments || ctx == null) {
                pendingAttachments[objectId] = name
            } else {
                ctx.end()
                replacements[objectId] = name
            }
        }
    }

    override fun objectsAdded(objects: List<DataObject>) {
        val specs = objects.mapNotNull { obj ->
            try {
                definitions.behavesAfterSpec(obj)
            } catch (e: IllegalStateException) {
                System.err.println("[StateMachineLifecycle] ${e.message}")
                null
            }
        }
        if (specs.isEmpty()) return
        synchronized(lock) {
            for (spec in specs) pendingAttachments[spec.objectId] = spec.definitionName!!
        }
    }

    override fun objectsRemoved(objectIds: Set<String>) {
        synchronized(lock) {
            for (id in objectIds) {
                live[id]?.end()
                replacements.remove(id)
                pendingAttachments.remove(id)
            }
        }
    }

    /**
     * Builds the instance of the definition [name] for [objectId], or null when the object is no longer in the model or
     * the instance cannot be built; the reason is written to the console.
     */
    private fun build(objectId: String, name: String): SMContext? {
        if (!modelQueryProcessor.containsObject(objectId)) return null
        val definition = definitions.named(name) ?: run {
            System.err.println("[StateMachineLifecycle] No attachable state machine is named '$name'")
            return null
        }
        return try {
            factory.build(InstanceSpec(objectId, name, definition), this)
        } catch (e: Exception) {
            System.err.println("[StateMachineLifecycle] Cannot attach '$name' to '$objectId': ${e.message}")
            null
        }
    }

    /** Makes [next] the order of instances, for the engine thread, the requests and [view]. */
    private fun install(next: List<SMContext>) {
        val frozen = next.toList()
        synchronized(lock) {
            live.clear()
            frozen.forEach { live[it.modelElementId] = it }
            instances = frozen
        }
        view = frozen.map { InstanceSpec(it.modelElementId, it.definitionName, it.stateMachine) }
    }

    private fun closeQuietly(ctx: SMContext) {
        try {
            ctx.macroProcessor?.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Learns the instances of state machines that join and leave the order of an engine. Called on the engine thread
 * inside the tick gate, by [StateMachineLifecycle.start] and [StateMachineLifecycle.applyChanges].
 */
interface StateMachineLifecycleListener {

    /**
     * [ctx] joined the order; it does its first step in the next tick. [predecessor] is the instance on the same object
     * that `EXCHANGE` replaced by [ctx], or null when [ctx] replaces none.
     */
    fun joined(ctx: SMContext, predecessor: SMContext?)

    /**
     * [ctx] left the order; it does no further step. [successor] is the instance that `EXCHANGE` put at its place, told
     * to [joined] afterwards, or null when no instance takes its place.
     */
    fun left(ctx: SMContext, successor: SMContext?)
}
