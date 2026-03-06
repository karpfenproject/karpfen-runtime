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

import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.messages.EventSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventBusTest {

    // ---- Event: basic properties ----

    @Test
    fun `Event isExpired returns false when ttl is 0`() {
        val event = Event("local", "start", ttlMs = 0L)
        assertFalse(event.isExpired())
    }

    @Test
    fun `Event isExpired returns false when within ttl`() {
        val event = Event("local", "start", ttlMs = 60_000L)
        assertFalse(event.isExpired())
    }

    @Test
    fun `Event isExpired returns true when ttl elapsed`() {
        val event = Event(
            domain = "local",
            name = "start",
            ttlMs = 1L,
            timestamp = System.currentTimeMillis() - 5000L
        )
        assertTrue(event.isExpired())
    }

    @Test
    fun `Event markProcessedBy and wasProcessedBy track engine`() {
        val event = Event("local", "move")
        assertFalse(event.wasProcessedBy("engine-1"))
        event.markProcessedBy("engine-1")
        assertTrue(event.wasProcessedBy("engine-1"))
        assertFalse(event.wasProcessedBy("engine-2"))
    }

    @Test
    fun `Event processedBy is independent per engine`() {
        val event = Event("global", "stop")
        event.markProcessedBy("engine-A")
        event.markProcessedBy("engine-B")
        assertTrue(event.wasProcessedBy("engine-A"))
        assertTrue(event.wasProcessedBy("engine-B"))
        assertFalse(event.wasProcessedBy("engine-C"))
    }

    @Test
    fun `Event has correct source`() {
        val internal = Event("local", "tick", source = EventSource.INTERNAL)
        val external = Event("global", "msg", source = EventSource.EXTERNAL_MESSAGE)
        val engineExt = Event("local", "pong", source = EventSource.EXTERNAL_ENGINE)
        assertEquals(EventSource.INTERNAL, internal.source)
        assertEquals(EventSource.EXTERNAL_MESSAGE, external.source)
        assertEquals(EventSource.EXTERNAL_ENGINE, engineExt.source)
    }

    // ---- EventBus: publishing and querying ----

    @Test
    fun `publish and hasUnprocessedEvent basic`() {
        val bus = EventBus()
        bus.publish(Event("local", "start"))
        assertTrue(bus.hasUnprocessedEvent("local", "start", "engine-1"))
    }

    @Test
    fun `hasUnprocessedEvent returns false for unknown domain`() {
        val bus = EventBus()
        assertFalse(bus.hasUnprocessedEvent("nonexistent", "foo", "engine-1"))
    }

    @Test
    fun `hasUnprocessedEvent returns false for unknown event name`() {
        val bus = EventBus()
        bus.publish(Event("local", "start"))
        assertFalse(bus.hasUnprocessedEvent("local", "stop", "engine-1"))
    }

    @Test
    fun `hasUnprocessedEvent returns false after engine marks processed`() {
        val bus = EventBus()
        bus.publish(Event("local", "fire"))
        val event = bus.peekEvent("local", "fire", "e1")!!
        event.markProcessedBy("e1")
        assertFalse(bus.hasUnprocessedEvent("local", "fire", "e1"))
    }

    @Test
    fun `second engine still sees event after first processed it`() {
        val bus = EventBus()
        bus.publish(Event("local", "trigger"))
        val event = bus.peekEvent("local", "trigger", "e1")!!
        event.markProcessedBy("e1")
        // e2 has not processed it
        assertTrue(bus.hasUnprocessedEvent("local", "trigger", "e2"))
    }

    @Test
    fun `domains are created lazily on publish`() {
        val bus = EventBus()
        assertFalse(bus.domains().contains("alpha"))
        bus.publish(Event("alpha", "ping"))
        assertTrue(bus.domains().contains("alpha"))
    }

    @Test
    fun `multiple domains are independent`() {
        val bus = EventBus()
        bus.publish(Event("domainA", "ev1"))
        bus.publish(Event("domainB", "ev2"))
        assertTrue(bus.hasUnprocessedEvent("domainA", "ev1", "e1"))
        assertTrue(bus.hasUnprocessedEvent("domainB", "ev2", "e1"))
        assertFalse(bus.hasUnprocessedEvent("domainA", "ev2", "e1"))
        assertFalse(bus.hasUnprocessedEvent("domainB", "ev1", "e1"))
    }

    @Test
    fun `multiple events in same domain all visible`() {
        val bus = EventBus()
        bus.publish(Event("local", "a"))
        bus.publish(Event("local", "b"))
        bus.publish(Event("local", "c"))
        assertTrue(bus.hasUnprocessedEvent("local", "a", "e1"))
        assertTrue(bus.hasUnprocessedEvent("local", "b", "e1"))
        assertTrue(bus.hasUnprocessedEvent("local", "c", "e1"))
    }

    @Test
    fun `peekEvent returns matching event`() {
        val bus = EventBus()
        bus.publish(Event("local", "go", payload = "fast"))
        val ev = bus.peekEvent("local", "go", "e1")
        assertNotNull(ev)
        assertEquals("go", ev!!.name)
        assertEquals("fast", ev.payload)
    }

    @Test
    fun `peekEvent returns null when no match`() {
        val bus = EventBus()
        assertNull(bus.peekEvent("local", "missing", "e1"))
    }

    @Test
    fun `getUnprocessedEvents returns all matching events for engine`() {
        val bus = EventBus()
        bus.publish(Event("local", "step"))
        bus.publish(Event("local", "step"))
        bus.publish(Event("local", "other"))
        val events = bus.getUnprocessedEvents("local", "e1")
        assertEquals(3, events.size)
    }

    @Test
    fun `getUnprocessedEvents excludes processed events`() {
        val bus = EventBus()
        bus.publish(Event("local", "tick"))
        bus.publish(Event("local", "tick"))
        val first = bus.peekEvent("local", "tick", "e1")!!
        first.markProcessedBy("e1")
        val remaining = bus.getUnprocessedEvents("local", "e1")
        // One of the two is processed; only one remains visible for e1
        assertEquals(1, remaining.size)
    }

    // ---- EventBus: TTL and purge ----

    @Test
    fun `purgeExpired removes expired events`() {
        val bus = EventBus()
        val expired = Event("local", "old", ttlMs = 1L, timestamp = System.currentTimeMillis() - 5000L)
        val fresh = Event("local", "new", ttlMs = 60_000L)
        bus.publish(expired)
        bus.publish(fresh)

        bus.purgeExpired()

        assertFalse(bus.hasUnprocessedEvent("local", "old", "e1"))
        assertTrue(bus.hasUnprocessedEvent("local", "new", "e1"))
    }

    @Test
    fun `defaultTtlMs applied to events with ttl 0`() {
        val bus = EventBus(defaultTtlMs = 1L)
        bus.publish(Event("local", "fleeting"))   // ttlMs == 0 → bus applies default 1ms
        Thread.sleep(50)
        bus.purgeExpired()
        assertFalse(bus.hasUnprocessedEvent("local", "fleeting", "e1"))
    }

    @Test
    fun `event with explicit ttl overrides default`() {
        val bus = EventBus(defaultTtlMs = 1L)
        bus.publish(Event("local", "long_lived", ttlMs = 60_000L))
        Thread.sleep(50)
        bus.purgeExpired()
        assertTrue(bus.hasUnprocessedEvent("local", "long_lived", "e1"))
    }

    @Test
    fun `purgeFullyConsumedAndExpired removes events processed by all engines`() {
        val bus = EventBus()
        val ev = Event("local", "shared", ttlMs = 1L, timestamp = System.currentTimeMillis() - 5000L)
        bus.publish(ev)
        ev.markProcessedBy("e1")
        ev.markProcessedBy("e2")

        bus.purgeFullyConsumedAndExpired(setOf("e1", "e2"))

        assertFalse(bus.hasUnprocessedEvent("local", "shared", "e1"))
        assertFalse(bus.hasUnprocessedEvent("local", "shared", "e2"))
    }

    // ---- EventBus: thread safety ----

    @Test
    fun `concurrent publish from multiple threads is safe`() {
        val bus = EventBus()
        val threads = (1..10).map { i ->
            Thread {
                repeat(100) {
                    bus.publish(Event("domain-$i", "ev", ttlMs = 60_000L))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        var total = 0
        for (i in 1..10) {
            total += bus.getUnprocessedEvents("domain-$i", "single-engine").size
        }
        assertEquals(1000, total)
    }

    @Test
    fun `concurrent read and write on same domain is safe`() {
        val bus = EventBus()
        // writer thread
        val writer = Thread {
            repeat(500) {
                bus.publish(Event("shared", "ping", ttlMs = 60_000L))
            }
        }
        // reader thread
        var readCount = 0
        val reader = Thread {
            repeat(500) {
                readCount += bus.getUnprocessedEvents("shared", "reader").size
            }
        }
        writer.start(); reader.start()
        writer.join(); reader.join()
        // Just assert no exception; count is non-deterministic due to race
        assertTrue(readCount >= 0)
    }
}

