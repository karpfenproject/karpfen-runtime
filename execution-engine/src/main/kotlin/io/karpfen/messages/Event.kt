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

import instance.DataObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The source of an event, indicating where it originated.
 */
enum class EventSource {
    /** Produced internally by the same state machine / engine instance. */
    INTERNAL,
    /** Produced by another state machine running in a separate engine thread. */
    EXTERNAL_ENGINE,
    /** Arrived from an outside client (e.g., via WebSocket or HTTP). */
    EXTERNAL_MESSAGE
}

/**
 * How the [Event.payload] string should be read.
 *
 * - [NONE]: there is no structured payload (an empty payload, or a pure signal event).
 * - [JSON]: the payload is a JSON object.
 * - [KMODEL]: the payload is written in the kmodel syntax (a single `make object` block).
 *
 * When a sender does not state the format, [detect] guesses it from the payload text.
 */
enum class PayloadFormat {
    NONE,
    JSON,
    KMODEL;

    companion object {
        /** Maps a textual format hint (e.g. from a WebSocket message) to a [PayloadFormat], or null if absent/unknown. */
        fun fromString(value: String?): PayloadFormat? = when (value?.trim()?.lowercase()) {
            "none", "" -> NONE
            "json" -> JSON
            "kmodel" -> KMODEL
            else -> null
        }

        /** Guesses the format from the payload text: empty → NONE, starts with `{` → JSON, starts with `make object` → KMODEL. */
        fun detect(payload: String): PayloadFormat {
            val trimmed = payload.trim()
            return when {
                trimmed.isEmpty() -> NONE
                trimmed.startsWith("{") -> JSON
                trimmed.startsWith("make object") -> KMODEL
                else -> NONE
            }
        }
    }
}

/**
 * Represents an event that can be produced and consumed by state machine engines.
 *
 * Events carry a [domain] (routing bucket), a [payload] string, a creation [timestamp],
 * the [source] category and the set of engine IDs that have already processed this event.
 * An event is considered expired once `System.currentTimeMillis() - timestamp > ttlMs`.
 *
 * @property domain      The domain / bucket name this event belongs to (e.g. "local", "global").
 * @property name        The event name / type (used for condition matching).
 * @property payload     The arbitrary string payload of the event.
 * @property timestamp   Wall-clock millisecond timestamp of when the runtime first saw this event.
 * @property source      Indicates the origin of the event.
 * @property ttlMs       Time-to-live in milliseconds; 0 means "live forever".
 * @property payloadFormat How [payload] should be read (none, JSON, or kmodel).
 */
class Event(
    val domain: String,
    val name: String,
    val payload: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val source: EventSource = EventSource.EXTERNAL_MESSAGE,
    val ttlMs: Long = 0L,
    val payloadFormat: PayloadFormat = PayloadFormat.NONE
) {
    /** Set of engine IDs that have already processed (reacted to) this event. Thread-safe. */
    private val processedByEngines: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The payload parsed into the runtime object model, or null when there is no payload (or it could
     * not be parsed). Populated once, at ingestion time, against the environment's event metamodel.
     * Once set, it is "captured" and stays available for as long as something holds onto this event,
     * independent of the event's TTL on the bus.
     */
    @Volatile
    var payloadObject: DataObject? = null

    /** Returns true when this event has outlived its TTL. Events with ttlMs == 0 never expire. */
    fun isExpired(): Boolean {
        if (ttlMs <= 0) return false
        return System.currentTimeMillis() - timestamp > ttlMs
    }

    /** Marks the event as processed by the given engine ID. */
    fun markProcessedBy(engineId: String) {
        processedByEngines.add(engineId)
    }

    /** Returns true if the given engine has already processed (reacted to) this event. */
    fun wasProcessedBy(engineId: String): Boolean = processedByEngines.contains(engineId)

    /** Returns true if any of the given engine ids has already processed this event. */
    fun wasProcessedByAny(engineIds: Set<String>): Boolean = engineIds.any { processedByEngines.contains(it) }

    override fun toString(): String =
        "Event(domain=$domain, name=$name, source=$source, timestamp=$timestamp, ttlMs=$ttlMs, processedBy=$processedByEngines)"
}
