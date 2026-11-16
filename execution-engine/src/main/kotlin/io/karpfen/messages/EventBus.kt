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
 * - When [purgeExpired] is called (typically once per engine tick), expired events are removed.
 * - An engine that has already reacted to an event marks it via [Event.markProcessedBy]; subsequent
 *   calls to [hasUnprocessedEvent] will return `false` for that engine.
 *
 * **Order**
 * A bucket keeps its events from the oldest to the newest [Event.timestamp], events of the same timestamp in the order
 * in which they were published. Every query answers in this order without sorting.
 *
 * **Cursors**
 * A reader that asks the same domain and name again and again, such as the branch of a state machine in every tick,
 * passes an [EventCursor] to [unhandledEvents]. The cursor remembers how far the front of the bucket holds only events
 * the reader has handled, events of other names and expired events. Since an event never becomes unhandled or live
 * again, the next query starts behind that front. A query therefore costs the events behind the front and not every
 * event on the bus, which keeps a tick cheap while thousands of handled events wait for their time to live to end.
 *
 * **Freezing**
 * While its environment is stopped, the bus is frozen ([freeze]): time does not pass for its events. [thaw] credits
 * every waiting event with the frozen time, so each keeps the time to live it had left at the freeze.
 *
 * **Thread safety**
 * The top-level domain map is a [ConcurrentHashMap]. Each bucket guards its events with its own lock, which every
 * method holds only for the work on that bucket.
 */
class EventBus(
    /** Default TTL in milliseconds applied to every published event (0 = live forever). */
    val defaultTtlMs: Long = 0L,
    /** The clock the bus measures the time to live of its events with, in milliseconds since 1970. */
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** The time of the clock at which the bus was frozen, or null while it is not frozen. */
    @Volatile
    private var frozenSince: Long? = null

    /** The time against which events expire: the time of the freeze while frozen, the clock otherwise. */
    private fun now(): Long = frozenSince ?: clock()

    /** Stops the time for the events of the bus, until [thaw]. Freezing a frozen bus changes nothing. */
    @Synchronized
    fun freeze() {
        if (frozenSince == null) frozenSince = clock()
    }

    /**
     * Lets the time go on for the events of the bus: every event in a bucket is credited with the time since the
     * [freeze], as [Event.creditFreeze] describes. Thawing a bus that is not frozen changes nothing.
     */
    @Synchronized
    fun thaw() {
        val since = frozenSince ?: return
        val thawedAt = clock()
        for (bucket in buckets.values) bucket.forEach { it.creditFreeze(since, thawedAt) }
        frozenSince = null
    }

    /** True while the bus is frozen. */
    val isFrozen: Boolean
        get() = frozenSince != null

    /** domain to its bucket */
    private val buckets: ConcurrentHashMap<String, Bucket> = ConcurrentHashMap()

    /** Listeners told about every published event; copy-on-write, so publishing never blocks on registration. */
    private val publicationListeners = CopyOnWriteArrayList<EventPublicationListener>()

    // ---- Publishing -------------------------------------------------------

    /**
     * Publishes an [event] to the bus. The event's [Event.domain] determines which bucket it
     * enters. The bucket is created automatically if it does not yet exist.
     *
     * If the event's own [Event.ttlMs] is 0 and [defaultTtlMs] > 0, the default TTL is used by
     * creating a new Event wrapper with the default TTL applied. Otherwise the event is stored as-is.
     *
     * After the effective event entered its bucket, every publication listener is called with it once, on the
     * publishing thread. A listener that throws an [Exception] is reported on stderr; the event stays published and
     * the other listeners are still called.
     */
    fun publish(event: Event) {
        val effectiveEvent = if (event.ttlMs <= 0 && defaultTtlMs > 0) {
            Event(
                domain = event.domain,
                name = event.name,
                payload = event.payload,
                timestamp = event.timestamp,
                source = event.source,
                ttlMs = defaultTtlMs,
                payloadFormat = event.payloadFormat
            ).also { it.payloadObject = event.payloadObject }
        } else {
            event
        }
        buckets.computeIfAbsent(effectiveEvent.domain) { Bucket() }.add(effectiveEvent)
        announce(effectiveEvent)
    }

    /** Registers [listener] for every event published from now on. */
    fun addPublicationListener(listener: EventPublicationListener) {
        publicationListeners.add(listener)
    }

    /** Stops telling [listener] about published events. */
    fun removePublicationListener(listener: EventPublicationListener) {
        publicationListeners.remove(listener)
    }

    /** Calls every publication listener with [event], reporting a failing listener on stderr without stopping. */
    private fun announce(event: Event) {
        for (listener in publicationListeners) {
            try {
                listener.onPublished(event)
            } catch (e: Exception) {
                System.err.println(
                    "[EventBus] Publication listener failed for event '${event.name}' in domain '${event.domain}': " +
                        "${e.message}"
                )
            }
        }
    }

    // ---- Querying ---------------------------------------------------------

    /**
     * Returns all non-expired events in [domain] that have not yet been processed by [engineId].
     *
     * This does **not** mark the events as processed; the caller must call
     * [Event.markProcessedBy] after reacting to an event.
     */
    fun getUnprocessedEvents(domain: String, engineId: String): List<Event> {
        val now = now()
        return buckets[domain]?.select { !it.isExpired(now) && !it.wasProcessedBy(engineId) } ?: emptyList()
    }

    /**
     * Returns all non-expired, unprocessed events with [eventName] in [domain] for [engineId],
     * ordered oldest-first. Used to scan candidate events when a transition has payload guards.
     */
    fun getUnprocessedEvents(domain: String, eventName: String, engineId: String): List<Event> {
        val now = now()
        return buckets[domain]?.select { it.name == eventName && !it.isExpired(now) && !it.wasProcessedBy(engineId) }
            ?: emptyList()
    }

    /**
     * Returns all non-expired events with [eventName] in [domain], oldest-first, regardless of which
     * engines have processed them.
     */
    fun eventsOf(domain: String, eventName: String): List<Event> {
        val now = now()
        return buckets[domain]?.select { it.name == eventName && !it.isExpired(now) } ?: emptyList()
    }

    /**
     * Returns the non-expired events with [eventName] in [domain] for which [handled] is false, oldest-first, and
     * moves [cursor] behind the front of the bucket that holds no such event.
     *
     * [handled] must stay true for an event once it was true, as "processed by one of a fixed set of ids" does, and
     * [cursor] must belong to one reader, one domain and one name, with the same [handled] in every call. The query
     * then starts behind the front that the cursor remembers, so its cost does not depend on how many handled events
     * wait in front of it.
     */
    fun unhandledEvents(
        domain: String,
        eventName: String,
        cursor: EventCursor,
        handled: (Event) -> Boolean
    ): List<Event> {
        val now = now()
        return buckets[domain]?.unhandled(eventName, now, cursor, handled) ?: emptyList()
    }

    /**
     * Returns the ids of [engineIds] that marked at least one event of the bus that has not expired. Goes through every
     * live event once and stops early when all of [engineIds] are found, so it costs the number of live events times
     * the markers of each.
     */
    fun markersAmong(engineIds: Set<String>): Set<String> {
        if (engineIds.isEmpty()) return emptySet()
        val now = now()
        val found = HashSet<String>()
        for (bucket in buckets.values) {
            bucket.collectMarkers(engineIds, now, found)
            if (found.size == engineIds.size) break
        }
        return found
    }

    /**
     * Returns true if at least one non-expired, unprocessed event with the given [eventName]
     * exists in [domain] for [engineId].
     */
    fun hasUnprocessedEvent(domain: String, eventName: String, engineId: String): Boolean =
        peekEvent(domain, eventName, engineId) != null

    /**
     * Returns the first non-expired, unprocessed event matching [eventName] in [domain],
     * or `null` if none exists.
     */
    fun peekEvent(domain: String, eventName: String, engineId: String): Event? {
        val now = now()
        return buckets[domain]?.first { it.name == eventName && !it.isExpired(now) && !it.wasProcessedBy(engineId) }
    }

    /**
     * Returns all domain names currently known to the bus (including empty ones).
     */
    fun domains(): Set<String> = buckets.keys.toSet()

    // ---- Maintenance ------------------------------------------------------

    /**
     * Removes ALL events from a single [domain] bucket regardless of TTL/processing.
     * Used by the force-set/resync API to drop pending events for one machine so they
     * cannot immediately transition it away from the snapped state.
     */
    fun clearDomain(domain: String) {
        buckets[domain]?.clear()
    }

    /**
     * Removes expired events. Called once per tick, it removes the expired events at the front of every bucket, which
     * costs only the events it removes, and it goes through a whole bucket when the bucket has doubled since the last
     * such pass. Expired events that wait behind a live one until then are never answered by a query.
     */
    fun purgeExpired() {
        val now = now()
        for (bucket in buckets.values) bucket.purge(now)
    }

    /**
     * Removes all events from a domain bucket that have been processed by **all** known engines
     * *and* are expired. This is a more conservative cleanup strategy for shared domains.
     */
    fun purgeFullyConsumedAndExpired(knownEngineIds: Set<String>) {
        val now = now()
        for (bucket in buckets.values) {
            bucket.removeIf { event -> event.isExpired(now) || knownEngineIds.all { event.wasProcessedBy(it) } }
        }
    }
}

/**
 * Where the front of a bucket ends for one reader, one domain and one name: the events up to it were handled by the
 * reader, had another name or were expired at the last query. Used by [EventBus.unhandledEvents] only.
 */
class EventCursor {

    /** The number of the last event of the front, or -1 before the first query. Guarded by the bucket. */
    internal var lastSeq = -1L

    /** The numbering of the bucket the cursor refers to. Guarded by the bucket. */
    internal var numbering = -1L
}

/**
 * The events of one domain, from the oldest to the newest timestamp. Each event gets a number in this order, which
 * cursors use to find their place again. An event published with a timestamp older than the newest one in the bucket,
 * which happens only when the clock went back, is put in its place, and the bucket numbers its events anew. Every
 * cursor then starts again from the front once.
 */
private class Bucket {

    private class Entry(val event: Event, var seq: Long)

    private val entries = ArrayDeque<Entry>()
    private var nextSeq = 0L
    private var numbering = 0L
    private var sizeAtLastSweep = 0

    @Synchronized
    fun add(event: Event) {
        val last = entries.lastOrNull()
        if (last == null || last.event.timestamp <= event.timestamp) {
            entries.addLast(Entry(event, nextSeq++))
            return
        }
        val at = entries.indexOfFirst { it.event.timestamp > event.timestamp }
        entries.add(at, Entry(event, 0))
        renumber()
    }

    @Synchronized
    fun select(keep: (Event) -> Boolean): List<Event> = entries.mapNotNull { e -> e.event.takeIf(keep) }

    @Synchronized
    fun first(match: (Event) -> Boolean): Event? = entries.firstOrNull { match(it.event) }?.event

    @Synchronized
    fun forEach(action: (Event) -> Unit) = entries.forEach { action(it.event) }

    @Synchronized
    fun unhandled(name: String, now: Long, cursor: EventCursor, handled: (Event) -> Boolean): List<Event> {
        var index = if (cursor.numbering == numbering) firstAfter(cursor.lastSeq) else 0
        cursor.numbering = numbering
        var front = true
        var result: MutableList<Event>? = null
        while (index < entries.size) {
            val entry = entries[index++]
            val event = entry.event
            val passed = event.name != name || event.isExpired(now) || handled(event)
            if (passed) {
                if (front) cursor.lastSeq = entry.seq
                continue
            }
            front = false
            (result ?: ArrayList<Event>().also { result = it }).add(event)
        }
        return result ?: emptyList()
    }

    @Synchronized
    fun collectMarkers(engineIds: Set<String>, now: Long, into: MutableSet<String>) {
        for (entry in entries) {
            if (into.size == engineIds.size) return
            if (!entry.event.isExpired(now)) entry.event.addMarkersAmong(engineIds, into)
        }
    }

    @Synchronized
    fun purge(now: Long) {
        while (entries.isNotEmpty() && entries.first().event.isExpired(now)) entries.removeFirst()
        if (entries.size >= 2 * sizeAtLastSweep + SWEEP_SLACK) {
            entries.removeAll { it.event.isExpired(now) }
            sizeAtLastSweep = entries.size
        }
    }

    @Synchronized
    fun removeIf(drop: (Event) -> Boolean) {
        entries.removeAll { drop(it.event) }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        sizeAtLastSweep = 0
    }

    /** The index of the first entry whose number is greater than [seq]. */
    private fun firstAfter(seq: Long): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].seq <= seq) low = mid + 1 else high = mid
        }
        return low
    }

    private fun renumber() {
        numbering++
        nextSeq = 0
        for (entry in entries) entry.seq = nextSeq++
    }

    private companion object {
        const val SWEEP_SLACK = 64
    }
}
