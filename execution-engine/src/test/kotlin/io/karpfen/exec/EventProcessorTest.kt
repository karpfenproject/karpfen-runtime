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
import io.karpfen.io.karpfen.messages.EventSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EventProcessorTest {

    private lateinit var bus: EventBus
    private lateinit var proc: EventProcessor

    @BeforeEach
    fun setUp() {
        bus = EventBus()
        proc = EventProcessor("engine-test", bus)
    }

    // ---- raiseInternalEvent ----

    @Test
    fun `raiseInternalEvent publishes to bus`() {
        proc.raiseInternalEvent("local", "start")
        assertTrue(proc.hasEvent("local", "start"))
    }

    @Test
    fun `raiseInternalEvent sets source to INTERNAL`() {
        proc.raiseInternalEvent("local", "tick")
        val ev = proc.peekEvent("local", "tick")
        assertNotNull(ev)
        assertEquals(EventSource.INTERNAL, ev!!.source)
    }

    @Test
    fun `raiseInternalEvent with custom ttl stores correct ttl`() {
        proc.raiseInternalEvent("local", "short_lived", ttlMs = 5000L)
        val ev = proc.peekEvent("local", "short_lived")
        assertNotNull(ev)
        assertFalse(ev!!.isExpired())
    }

    @Test
    fun `raiseInternalEvent uses bus default ttl when none given`() {
        val busWithDefault = EventBus(defaultTtlMs = 60_000L)
        val procWithDefault = EventProcessor("e1", busWithDefault)
        procWithDefault.raiseInternalEvent("local", "ev")
        val ev = busWithDefault.peekEvent("local", "ev", "e1")
        assertNotNull(ev)
        assertFalse(ev!!.isExpired())
    }

    // ---- publishExternalEvent ----

    @Test
    fun `publishExternalEvent adds event to bus`() {
        val ev = Event("global", "ping", source = EventSource.EXTERNAL_MESSAGE)
        proc.publishExternalEvent(ev)
        assertTrue(proc.hasEvent("global", "ping"))
    }

    @Test
    fun `publishExternalEvent from engine source is visible`() {
        val ev = Event("shared", "update", source = EventSource.EXTERNAL_ENGINE)
        proc.publishExternalEvent(ev)
        assertTrue(bus.hasUnprocessedEvent("shared", "update", "engine-test"))
    }

    // ---- hasEvent ----

    @Test
    fun `hasEvent returns false for empty bus`() {
        assertFalse(proc.hasEvent("local", "anything"))
    }

    @Test
    fun `hasEvent returns false after event consumed`() {
        proc.raiseInternalEvent("local", "go")
        proc.consumeEvent("local", "go")
        assertFalse(proc.hasEvent("local", "go"))
    }

    @Test
    fun `hasEvent returns false for expired event`() {
        val expired = Event("local", "old", ttlMs = 1L, timestamp = System.currentTimeMillis() - 5000L)
        proc.publishExternalEvent(expired)
        assertFalse(proc.hasEvent("local", "old"))
    }

    // ---- peekEvent ----

    @Test
    fun `peekEvent returns event without consuming`() {
        proc.raiseInternalEvent("local", "peek")
        val ev1 = proc.peekEvent("local", "peek")
        val ev2 = proc.peekEvent("local", "peek")
        assertNotNull(ev1)
        assertNotNull(ev2)
        assertSame(ev1, ev2)
    }

    @Test
    fun `peekEvent returns null when no event present`() {
        assertNull(proc.peekEvent("local", "none"))
    }

    // ---- consume ----

    @Test
    fun `consume marks event as processed by this engine`() {
        proc.raiseInternalEvent("local", "act")
        val ev = proc.peekEvent("local", "act")!!
        proc.consume(ev)
        assertTrue(ev.wasProcessedBy("engine-test"))
        assertFalse(proc.hasEvent("local", "act"))
    }

    // ---- consumeEvent ----

    @Test
    fun `consumeEvent returns and marks event`() {
        proc.raiseInternalEvent("local", "fire")
        val consumed = proc.consumeEvent("local", "fire")
        assertNotNull(consumed)
        assertEquals("fire", consumed!!.name)
        assertFalse(proc.hasEvent("local", "fire"))
    }

    @Test
    fun `consumeEvent returns null when nothing to consume`() {
        assertNull(proc.consumeEvent("local", "nothing"))
    }

    // ---- purgeExpired ----

    @Test
    fun `purgeExpired removes old events`() {
        val expired = Event("local", "stale", ttlMs = 1L, timestamp = System.currentTimeMillis() - 1000L)
        proc.publishExternalEvent(expired)
        proc.purgeExpired()
        assertFalse(proc.hasEvent("local", "stale"))
    }

    @Test
    fun `purgeExpired keeps live events`() {
        proc.raiseInternalEvent("local", "alive", ttlMs = 60_000L)
        proc.purgeExpired()
        assertTrue(proc.hasEvent("local", "alive"))
    }

    // ---- knownDomains ----

    @Test
    fun `knownDomains reflects published domains`() {
        proc.raiseInternalEvent("alpha", "e1")
        proc.raiseInternalEvent("beta", "e2")
        val domains = proc.knownDomains()
        assertTrue(domains.contains("alpha"))
        assertTrue(domains.contains("beta"))
    }

    // ---- Multi-engine scenario ----

    @Test
    fun `two processors share bus - event visible to both`() {
        val sharedBus = EventBus()
        val proc1 = EventProcessor("engine-1", sharedBus)
        val proc2 = EventProcessor("engine-2", sharedBus)

        proc1.raiseInternalEvent("global", "shared_event")

        assertTrue(proc1.hasEvent("global", "shared_event"))
        assertTrue(proc2.hasEvent("global", "shared_event"))
    }

    @Test
    fun `engine 1 consuming event does not affect engine 2`() {
        val sharedBus = EventBus()
        val proc1 = EventProcessor("engine-1", sharedBus)
        val proc2 = EventProcessor("engine-2", sharedBus)

        proc1.raiseInternalEvent("global", "cross_event")
        proc1.consumeEvent("global", "cross_event")

        assertFalse(proc1.hasEvent("global", "cross_event"))
        assertTrue(proc2.hasEvent("global", "cross_event"))
    }

    @Test
    fun `external engine event is visible on shared bus`() {
        val sharedBus = EventBus()
        val proc1 = EventProcessor("engine-1", sharedBus)
        val proc2 = EventProcessor("engine-2", sharedBus)

        val ev = Event("statemachine-2", "done", source = EventSource.EXTERNAL_ENGINE)
        proc2.publishExternalEvent(ev)

        assertTrue(proc1.hasEvent("statemachine-2", "done"))
    }
}

