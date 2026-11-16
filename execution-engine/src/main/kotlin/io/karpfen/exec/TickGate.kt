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

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Separates the engine's ticks from readers that need the model at rest.
 *
 * Every write to the model happens inside a tick: the queued engine commands, the purge of expired events, and for
 * each state machine its `ENTRY` and `DO` blocks (each a notification batch published when the block ends) followed by
 * its active-stack notification. The blocks run on the engine thread or, while the feature TrueParallel is on, on the
 * threads of the branches of split state machines, which the engine thread waits for inside the tick. The engine runs
 * each tick inside [duringTick] and sleeps outside it. A read inside [betweenTicks] therefore sees the model after a
 * whole number of ticks, and every push of those ticks has already been enqueued. The one exception is a tick that is
 * halted at a breakpoint, see [outsideTick].
 *
 * The gate is a fair [Semaphore] with one permit rather than a lock, because the thread that opens the gate for a halt
 * at a breakpoint is not always the engine thread that took it for the tick: a branch stepping on its own thread
 * halts there too. Fairness lets a waiting reader in before the engine's next tick, so it cannot starve even with a
 * tick delay of 0. The gate is free whenever no tick runs, so the same read works before the engine starts, after it
 * stops and after its thread ended on its own.
 */
class TickGate {

    /** The one permit of the gate; a tick holds it from start to end, a reader while it reads. */
    private val permit = Semaphore(1, true)

    /**
     * True while a running tick holds the [permit], false between ticks and while a halt in [outsideTick] has lent the
     * permit to readers.
     */
    private val tickHolds = AtomicBoolean(false)

    /**
     * Runs one [tick] while holding the gate. An exception thrown by the tick releases the gate and propagates.
     */
    fun <T> duringTick(tick: () -> T): T {
        permit.acquire()
        tickHolds.set(true)
        try {
            return tick()
        } finally {
            tickHolds.set(false)
            permit.release()
        }
    }

    /**
     * Runs [wait] with the gate left open and takes the gate again afterwards, also when [wait] throws. A thread calls
     * it while it is halted inside a tick, at a breakpoint: the engine thread, or the thread of a branch that steps in
     * parallel. Readers then do not wait for the halt to end. A reader sees the model as the halted tick has left it so
     * far: every `ENTRY` or `DO` block that ran is complete, the rest of the tick is still to come, and branches that
     * step in parallel may still be inside their steps. Outside a tick, and while another halt has already opened the
     * gate, the call only runs [wait].
     */
    fun outsideTick(wait: () -> Unit) {
        if (!tickHolds.compareAndSet(true, false)) {
            wait()
            return
        }
        permit.release()
        try {
            wait()
        } finally {
            permit.acquireUninterruptibly()
            tickHolds.set(true)
        }
    }

    /**
     * Runs [read] at the next tick boundary, waiting at most [timeoutMs] milliseconds for the running tick to leave
     * the gate.
     *
     * @return the result of [read], or `null` when no tick boundary came within the timeout; [read] is not run then.
     */
    fun <T> betweenTicks(timeoutMs: Long, read: () -> T): T? {
        if (!permit.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            return null
        }
        try {
            return read()
        } finally {
            permit.release()
        }
    }
}
