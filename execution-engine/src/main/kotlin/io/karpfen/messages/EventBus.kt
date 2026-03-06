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
package io.karpfen.io.karpfen.messages

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A shared, thread-safe event bus that organises events into domain buckets.
 *
 * Domains are created lazily as soon as the first event with a new domain name is published.
 * All [io.karpfen.Engine] instances running in the same environment share one [EventBus],
 * so events from one engine are immediately visible to all others.
 *
 * **Event lifecycle**
 * - An event lives in its domain bucket until its TTL expires.
 * - When [purgeExpired] is called (typically once per engine tick), all expired events are removed.
 * - An engine that has already reacted to an event marks it via [Event.markProcessedBy]; subsequent
 *   calls to [hasUnprocessedEvent] will return `false` for that engine.
 *
 * **Thread safety**
 * The top-level domain map is a [ConcurrentHashMap]. Each bucket is a [CopyOnWriteArrayList],
 * which allows cheap concurrent reads while keeping write operations safe.
 */
class EventBus(
    /** Default TTL in milliseconds applied to every published event (0 = live forever). */
    val defaultTtlMs: Long = 0L
) {
    /** domain → list of live events */
    private val buckets: ConcurrentHashMap<String, CopyOnWriteArrayList<Event>> = ConcurrentHashMap()

    // ---- Publishing -------------------------------------------------------

    /**
     * Publishes an [event] to the bus. The event's [Event.domain] determines which bucket it
     * enters. The bucket is created automatically if it does not yet exist.
     *
     * If the event's own [Event.ttlMs] is 0 and [defaultTtlMs] > 0, the default TTL is used by
     * creating a new Event wrapper with the default TTL applied. Otherwise the event is stored as-is.
     */
    fun publish(event: Event) {
        val effectiveEvent = if (event.ttlMs <= 0 && defaultTtlMs > 0) {
            Event(
                domain = event.domain,
                name = event.name,
                payload = event.payload,
                timestamp = event.timestamp,
                source = event.source,
                ttlMs = defaultTtlMs
            )
        } else {
            event
        }
        getOrCreateBucket(effectiveEvent.domain).add(effectiveEvent)
    }

    // ---- Querying ---------------------------------------------------------

    /**
     * Returns all non-expired events in [domain] that have not yet been processed by [engineId].
     *
     * This does **not** mark the events as processed; the caller must call
     * [Event.markProcessedBy] after reacting to an event.
     */
    fun getUnprocessedEvents(domain: String, engineId: String): List<Event> {
        return getBucket(domain)
            .filter { !it.isExpired() && !it.wasProcessedBy(engineId) }
    }

    /**
     * Returns true if at least one non-expired, unprocessed event with the given [eventName]
     * exists in [domain] for [engineId].
     */
    fun hasUnprocessedEvent(domain: String, eventName: String, engineId: String): Boolean {
        return getBucket(domain).any { event ->
            !event.isExpired() && !event.wasProcessedBy(engineId) && event.name == eventName
        }
    }

    /**
     * Returns the first non-expired, unprocessed event matching [eventName] in [domain],
     * or `null` if none exists.
     */
    fun peekEvent(domain: String, eventName: String, engineId: String): Event? {
        return getBucket(domain).firstOrNull { event ->
            !event.isExpired() && !event.wasProcessedBy(engineId) && event.name == eventName
        }
    }

    /**
     * Returns all domain names currently known to the bus (including empty ones).
     */
    fun domains(): Set<String> = buckets.keys.toSet()

    // ---- Maintenance ------------------------------------------------------

    /**
     * Removes all expired events from every bucket. Should be called periodically,
     * e.g., once per engine tick.
     */
    fun purgeExpired() {
        for ((_, bucket) in buckets) {
            bucket.removeIf { it.isExpired() }
        }
    }

    /**
     * Removes all events from a domain bucket that have been processed by **all** known engines
     * *and* are expired. This is a more conservative cleanup strategy for shared domains.
     */
    fun purgeFullyConsumedAndExpired(knownEngineIds: Set<String>) {
        for ((_, bucket) in buckets) {
            bucket.removeIf { event ->
                event.isExpired() || knownEngineIds.all { engineId -> event.wasProcessedBy(engineId) }
            }
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private fun getOrCreateBucket(domain: String): CopyOnWriteArrayList<Event> {
        return buckets.getOrPut(domain) { CopyOnWriteArrayList() }
    }

    private fun getBucket(domain: String): CopyOnWriteArrayList<Event> {
        return buckets[domain] ?: CopyOnWriteArrayList()
    }
}

