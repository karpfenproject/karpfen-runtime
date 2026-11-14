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
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * While the bus is frozen, time does not pass for its events: each keeps the time to live it had left at the freeze,
 * and an event that arrives during the freeze starts to age only after the thaw. The bus runs on a clock the test sets.
 */
class EventBusFreezeTest {

    /** The time of the test clock, in milliseconds. */
    private var now = 1_000L

    private val bus = EventBus(clock = { now })

    @Test
    fun `an event keeps the time to live it had left at the freeze`() {
        bus.publish(Event("d", "go", timestamp = 1_000L, ttlMs = 100L))
        now = 1_050L
        bus.freeze()
        now = 9_000L
        assertTrue(bus.hasUnprocessedEvent("d", "go", "engine"), "a frozen bus lets no time pass")

        bus.thaw()
        now = 9_049L
        assertTrue(bus.hasUnprocessedEvent("d", "go", "engine"))
        now = 9_051L
        assertFalse(bus.hasUnprocessedEvent("d", "go", "engine"))
    }

    @Test
    fun `an event that arrives during the freeze ages only after the thaw`() {
        bus.freeze()
        now = 5_000L
        bus.publish(Event("d", "go", timestamp = 5_000L, ttlMs = 100L))
        now = 9_000L
        bus.thaw()

        now = 9_100L
        assertTrue(bus.hasUnprocessedEvent("d", "go", "engine"))
        now = 9_101L
        bus.purgeExpired()
        assertEquals(emptyList(), bus.eventsOf("d", "go"))
    }

    @Test
    fun `freezing twice and thawing twice count the frozen time once`() {
        bus.publish(Event("d", "go", timestamp = 1_000L, ttlMs = 100L))
        bus.freeze()
        now = 2_000L
        bus.freeze()
        now = 3_000L
        bus.thaw()
        bus.thaw()

        now = 3_100L
        assertTrue(bus.hasUnprocessedEvent("d", "go", "engine"))
        now = 3_101L
        assertFalse(bus.hasUnprocessedEvent("d", "go", "engine"))
    }
}
