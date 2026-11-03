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

import io.karpfen.io.karpfen.exec.TickGate
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers [TickGate]: a read waits for the running tick, gives up after its timeout, and passes at once between ticks.
 * The tests synchronize with latches and the state of the waiting thread, never with sleeps.
 */
class TickGateTest {

    /** The gate under test. */
    private val gate = TickGate()

    /** Opens once the test's tick holds the gate. */
    private val tickEntered = CountDownLatch(1)

    /** Lets the test's tick leave the gate when opened. */
    private val tickReleased = CountDownLatch(1)

    @Test
    fun `a read waits until the running tick leaves the gate`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val ticker = startTick { order.add("tick ended") }
        val result = AtomicReference<String?>()
        val reader = thread(name = "tick-gate-reader") {
            result.set(gate.betweenTicks(WAIT_MS) { order.add("read"); "model" })
        }

        awaitState(reader, Thread.State.TIMED_WAITING)
        assertTrue(order.isEmpty())
        tickReleased.countDown()
        reader.join(WAIT_MS)
        ticker.join(WAIT_MS)

        assertEquals("model", result.get())
        assertEquals(listOf("tick ended", "read"), order)
    }

    @Test
    fun `a read gives up and returns null when no tick boundary comes within the timeout`() {
        val ticker = startTick { }
        var readRan = false

        val result = gate.betweenTicks(1_000) { readRan = true; "model" }

        tickReleased.countDown()
        ticker.join(WAIT_MS)
        assertNull(result)
        assertFalse(readRan)
    }

    @Test
    fun `a read passes at once when no tick is running`() {
        assertEquals("model", gate.betweenTicks(0) { "model" })
    }

    /** Starts a tick on its own thread that holds the gate until [tickReleased] opens, then runs [atEnd]. */
    private fun startTick(atEnd: () -> Unit): Thread {
        val ticker = thread(name = "tick-gate-ticker") {
            gate.duringTick {
                tickEntered.countDown()
                tickReleased.await()
                atEnd()
            }
        }
        if (!tickEntered.await(WAIT_MS, TimeUnit.MILLISECONDS)) fail("The tick did not enter the gate")
        return ticker
    }

    /** Waits until [waiting] is parked in [state], failing after a generous deadline. */
    private fun awaitState(waiting: Thread, state: Thread.State) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (waiting.state != state) {
            if (System.nanoTime() > deadline) fail("${waiting.name} never reached $state, it is ${waiting.state}")
            Thread.onSpinWait()
        }
    }

    private companion object {
        /** Generous deadline for every wait of these tests. */
        const val WAIT_MS = 10_000L
    }
}
