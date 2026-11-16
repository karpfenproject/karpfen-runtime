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
package io.karpfen.exec

import io.karpfen.io.karpfen.exec.EventProcessor
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The cursor a reader keeps per domain and name must never let it miss an event. These cases follow a reader through
 * the changes of a bucket that move or remove what lies in front of the cursor: an event with an older timestamp that
 * makes the bucket number its events anew, a bucket that is empty after such a renumbering, and a sweep of expired
 * events while the bus is frozen and thawed. The bus runs on a clock the test sets.
 */
class EventCursorTest {

    private var clock = 1_000L
    private val bus = EventBus(defaultTtlMs = 0L) { clock }

    private fun publish(name: String, timestamp: Long = clock, ttlMs: Long = 0L) =
        Event("d", name, timestamp = timestamp, ttlMs = ttlMs).also { bus.publish(it) }

    /** Lets the reader consume five events of `go` that expire, and removes them from the bus. */
    private fun consumeFiveAndPurge(reader: EventProcessor) {
        repeat(5) {
            val event = publish("go", ttlMs = 10L)
            assertEquals(listOf(event), reader.getEvents("d", "go"))
            reader.consume(event)
            clock++
        }
        assertEquals(emptyList<Event>(), reader.getEvents("d", "go"))
        clock += 100
        bus.purgeExpired()
    }

    @Test
    fun `after a renumbering with an unhandled event at the front the reader still sees every later event`() {
        val reader = EventProcessor("p0", bus)
        consumeFiveAndPurge(reader)

        publish("other")
        val older = publish("go", timestamp = clock - 5)
        assertEquals(listOf(older), reader.getEvents("d", "go"))
        reader.consume(older)
        assertEquals(emptyList<Event>(), reader.getEvents("d", "go"))

        val later = (1..4).map { clock++; publish("go") }
        assertEquals(later, reader.getEvents("d", "go"), "every event behind the renumbered ones is seen")
    }

    @Test
    fun `after a renumbering of a bucket that is empty at the query the reader still sees later events`() {
        val reader = EventProcessor("p0", bus)
        consumeFiveAndPurge(reader)

        publish("other", ttlMs = 10L)
        publish("go", timestamp = clock - 5, ttlMs = 10L)
        clock += 100
        bus.purgeExpired()
        assertEquals(emptyList<Event>(), reader.getEvents("d", "go"))

        val later = (1..4).map { clock++; publish("go") }
        assertEquals(later, reader.getEvents("d", "go"), "every event published after the empty query is seen")
    }

    @Test
    fun `a sweep and a freeze leave every reader its unhandled events`() {
        val left = EventProcessor("p1", bus)
        val right = EventProcessor("p2", bus)
        val waiting = publish("go")
        val expiring = (1..80).map { clock++; publish("go", ttlMs = 500L) }
        val consumed = publish("go")
        assertEquals(listOf(waiting) + expiring + consumed, left.getEvents("d", "go"))
        left.consume(waiting)
        left.consume(consumed)

        bus.freeze()
        clock += 1_000
        assertEquals(expiring, left.getEvents("d", "go"), "no time passes for the events while the bus is frozen")
        val duringFreeze = publish("go")
        bus.thaw()

        clock += 600
        bus.purgeExpired()
        assertEquals(listOf(duringFreeze), left.getEvents("d", "go"), "the sweep removed the expired events")
        assertEquals(listOf(waiting, consumed, duringFreeze), right.getEvents("d", "go"))
        left.consume(duringFreeze)
        clock++
        val after = publish("go")
        assertEquals(listOf(after), left.getEvents("d", "go"))
        assertEquals(listOf(waiting, consumed, duringFreeze, after), right.getEvents("d", "go"))
    }
}
