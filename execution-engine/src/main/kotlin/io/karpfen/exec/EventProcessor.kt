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

import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.messages.EventSource

/**
 * Per-identity facade for the shared [EventBus].
 *
 * An [EventProcessor] reacts to events on behalf of one identity ([engineId]) plus an optional
 * [inheritedIds] lineage. An event is considered "already handled" by this processor when any id in
 * its lineage has processed it. Consuming an event marks only [engineId].
 *
 * This is what makes parallel branches independent: each branch has its own processor, so a branch
 * can react to an event another branch already consumed. A branch created by a split inherits its
 * parent's lineage (so it does not re-handle what the parent already did), and a branch created by a
 * join inherits the union of the joined branches' lineages.
 *
 * @property engineId     Unique identifier used when consuming (marking) events.
 * @property eventBus     The shared [EventBus] instance for the environment.
 * @property inheritedIds Lineage ids inherited from ancestor branches; events handled by any of them
 *                        are treated as already handled by this processor.
 */
class EventProcessor(
    val engineId: String,
    val eventBus: EventBus,
    val inheritedIds: Set<String> = emptySet()
) {

    /** The full set of ids whose processing counts as "handled" for this processor. */
    private val effectiveIds: Set<String> = inheritedIds + engineId

    /** Returns this processor's full lineage, used to seed descendant branches on split/join. */
    fun lineage(): Set<String> = effectiveIds

    // ---- Publishing -------------------------------------------------------

    /**
     * Publishes an event from an external source (e.g. WebSocket client) to the bus.
     * The event is stored as-is; its [Event.source] should already be set correctly.
     */
    fun publishExternalEvent(event: Event) {
        eventBus.publish(event)
    }

    /**
     * Publishes an internal event raised by this engine's state machine.
     * Convenience overload that creates the event with [EventSource.INTERNAL].
     */
    fun raiseInternalEvent(domain: String, name: String, payload: String = "", ttlMs: Long = 0L) {
        val event = Event(
            domain = domain,
            name = name,
            payload = payload,
            timestamp = System.currentTimeMillis(),
            source = EventSource.INTERNAL,
            ttlMs = ttlMs.takeIf { it > 0 } ?: eventBus.defaultTtlMs
        )
        eventBus.publish(event)
    }

    // ---- Querying ---------------------------------------------------------

    /**
     * Returns true if the given [domain] contains at least one event with [eventName] that this
     * processor's lineage has not yet handled.
     */
    fun hasEvent(domain: String, eventName: String): Boolean {
        return eventBus.eventsOf(domain, eventName).any { !it.wasProcessedByAny(effectiveIds) }
    }

    /**
     * Returns the first matching event not yet handled by this processor's lineage, or null.
     */
    fun peekEvent(domain: String, eventName: String): Event? {
        return eventBus.eventsOf(domain, eventName).firstOrNull { !it.wasProcessedByAny(effectiveIds) }
    }

    /**
     * Returns all events of [eventName] in [domain] not yet handled by this processor's lineage,
     * oldest-first. Used to scan candidate events when a transition guards on the payload.
     */
    fun getEvents(domain: String, eventName: String): List<Event> {
        return eventBus.eventsOf(domain, eventName).filter { !it.wasProcessedByAny(effectiveIds) }
    }

    /**
     * Marks the given event as processed by this engine.
     * Call this after the engine has reacted to the event (e.g. a transition fired).
     */
    fun consume(event: Event) {
        event.markProcessedBy(engineId)
    }

    /**
     * Marks the first matching event as processed by this engine and returns it, or null if none.
     * Convenience combination of [peekEvent] + [consume].
     */
    fun consumeEvent(domain: String, eventName: String): Event? {
        val event = peekEvent(domain, eventName) ?: return null
        consume(event)
        return event
    }

    // ---- Maintenance ------------------------------------------------------

    /**
     * Removes all expired events from the bus. Should be called once per tick.
     */
    fun purgeExpired() {
        eventBus.purgeExpired()
    }

    /**
     * Returns all currently known domain names in the bus.
     */
    fun knownDomains(): Set<String> = eventBus.domains()
}

