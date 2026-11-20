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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule of the lineage of a branch, on [EventProcessor]s the way splits and joins build them: a branch does not
 * handle again what one of its ancestors handled, while a sibling and later events stay apart. The lineage a branch
 * hands on holds only the ids that marked an event still on the bus, so it does not grow with the number of splits.
 */
class EventLineageTest {

    private var clock = 1_000L
    private val bus = EventBus(defaultTtlMs = 0L) { clock }

    private fun publish(name: String) = Event("d", name, timestamp = clock).also { bus.publish(it) }

    @Test
    fun `a split child skips what its parent handled, but sees what came later and what its sibling handled`() {
        val parent = EventProcessor("p0", bus)
        val consumed = publish("go")
        parent.consume(consumed)
        val waiting = publish("go")

        val left = EventProcessor("p1", bus, parent.lineage())
        val right = EventProcessor("p2", bus, parent.lineage())

        assertEquals(listOf(waiting), left.getEvents("d", "go"))
        left.consume(waiting)
        assertEquals(emptyList<Event>(), left.getEvents("d", "go"))
        assertEquals(listOf(waiting), right.getEvents("d", "go"), "a sibling handles on its own")
        clock++
        val later = publish("go")
        assertEquals(listOf(later), left.getEvents("d", "go"))
    }

    @Test
    fun `the branch of a join does not handle what any of the joined branches or their ancestors handled`() {
        val parent = EventProcessor("p0", bus)
        val early = publish("go")
        parent.consume(early)
        val left = EventProcessor("p1", bus, parent.lineage())
        val right = EventProcessor("p2", bus, parent.lineage())
        val a = publish("go")
        val b = publish("go")
        val c = publish("go")
        left.consume(a)
        right.consume(b)

        val joined = EventProcessor("p3", bus, left.lineage() + right.lineage())

        assertEquals(listOf(c), joined.getEvents("d", "go"))
        assertTrue(joined.hasEvent("d", "go"))
        assertEquals(c, joined.peekEvent("d", "go"))
    }

    @Test
    fun `a lineage keeps only ids that marked an event still on the bus`() {
        var branch = EventProcessor("p0", bus)
        val kept = publish("go")
        branch.consume(kept)
        val gone = Event("d", "go", timestamp = clock, ttlMs = 100L).also { bus.publish(it) }

        repeat(1000) { cycle ->
            val left = EventProcessor("l$cycle", bus, branch.lineage())
            val right = EventProcessor("r$cycle", bus, branch.lineage())
            if (cycle == 0) left.consume(gone)
            branch = EventProcessor("j$cycle", bus, left.lineage() + right.lineage())
        }
        assertEquals(setOf("p0", "l0"), branch.lineage(), "while both events are on the bus")
        assertFalse(branch.hasEvent("d", "go"))

        clock += 1000
        bus.purgeExpired()
        branch = EventProcessor("next", bus, branch.lineage())

        assertEquals(setOf("p0"), branch.lineage(), "the id that marked only the expired event has left")
        assertFalse(branch.hasEvent("d", "go"), "the event of the ancestor that is still there stays handled")
    }

    @Test
    fun `an event that comes with an older timestamp is still found and answered in its place`() {
        val reader = EventProcessor("p0", bus)
        val first = publish("go")
        reader.consume(first)
        assertEquals(emptyList<Event>(), reader.getEvents("d", "go"))
        clock += 10
        val newer = publish("go")
        val older = Event("d", "go", timestamp = clock - 5).also { bus.publish(it) }

        assertEquals(listOf(older, newer), reader.getEvents("d", "go"))
    }

    @Test
    fun `events of other names and expired events do not confuse a reader`() {
        val reader = EventProcessor("p0", bus)
        bus.publish(Event("d", "other", timestamp = clock))
        val expiring = Event("d", "go", timestamp = clock, ttlMs = 100L).also { bus.publish(it) }
        assertEquals(listOf(expiring), reader.getEvents("d", "go"))

        clock += 200
        assertEquals(emptyList<Event>(), reader.getEvents("d", "go"))
        bus.purgeExpired()
        val fresh = Event("d", "go", timestamp = clock).also { bus.publish(it) }
        assertEquals(listOf(fresh), reader.getEvents("d", "go"))
    }

    @Test
    fun `a processor whose id marked all live events skips them on every domain, while others and later events stay apart`() {
        val other = EventProcessor("o0", bus)
        val here = publish("go")
        val elsewhere = Event("e", "go", timestamp = clock).also { bus.publish(it) }
        val forced = EventProcessor("p0", bus)
        bus.markLiveEventsProcessedBy(setOf(forced.engineId))

        assertEquals(emptyList<Event>(), forced.getEvents("d", "go"))
        assertEquals(emptyList<Event>(), forced.getEvents("e", "go"))
        assertEquals(listOf(here), other.getEvents("d", "go"))
        assertEquals(listOf(elsewhere), other.getEvents("e", "go"))

        clock++
        val later = publish("go")
        assertEquals(listOf(later), forced.getEvents("d", "go"))
        val child = EventProcessor("p1", bus, forced.lineage())
        assertEquals(listOf(later), child.getEvents("d", "go"), "a descendant skips what the processor handled")
    }
}
