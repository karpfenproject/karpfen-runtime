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
 * Per-engine facade for the shared [EventBus].
 *
 * Each [Engine] owns one [EventProcessor] that knows its own [engineId]. The processor
 * delegates all persistence and routing to the shared bus, but adds the engine-specific
 * perspective:
 * - Publishing events always tags them with [EventSource.INTERNAL] by default.
 * - Querying events filters out events already processed by this engine.
 *
 * @property engineId  Unique identifier for the owning engine instance.
 * @property eventBus  The shared [EventBus] instance for the environment.
 */
class EventProcessor(
    val engineId: String,
    val eventBus: EventBus
) {

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
     * Returns true if the given [domain] contains at least one non-expired, unprocessed event
     * with [eventName] for this engine.
     */
    fun hasEvent(domain: String, eventName: String): Boolean {
        return eventBus.hasUnprocessedEvent(domain, eventName, engineId)
    }

    /**
     * Returns the first matching unprocessed event, or null.
     */
    fun peekEvent(domain: String, eventName: String): Event? {
        return eventBus.peekEvent(domain, eventName, engineId)
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

