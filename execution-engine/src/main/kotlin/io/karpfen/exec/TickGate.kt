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

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Separates the engine's ticks from readers that need the model at rest.
 *
 * Every write to the model happens on the engine thread inside a tick: the queued engine commands, the purge of
 * expired events, and for each state machine its `ENTRY` and `DO` blocks (each a notification batch published when
 * the block ends) followed by its active-stack notification. The engine runs each tick inside [duringTick] and sleeps
 * outside it. A read inside [betweenTicks] therefore sees the model after a whole number of ticks, and every push of
 * those ticks has already been enqueued.
 *
 * The gate is a fair [ReentrantLock]: a waiting reader gets the lock before the engine's next tick, so it cannot
 * starve even with a tick delay of 0. The gate is free whenever no tick runs, so the same read works before the engine
 * starts, after it stops and after its thread ended on its own.
 */
class TickGate {

    /** Held by the engine thread for the whole of each tick; fair, so readers are served in arrival order. */
    private val lock = ReentrantLock(true)

    /**
     * Runs one [tick] while holding the gate. An exception thrown by the tick releases the gate and propagates.
     */
    fun <T> duringTick(tick: () -> T): T = lock.withLock(tick)

    /**
     * Runs [read] at the next tick boundary, waiting at most [timeoutMs] milliseconds for the running tick to leave
     * the gate.
     *
     * @return the result of [read], or `null` when no tick boundary came within the timeout; [read] is not run then.
     */
    fun <T> betweenTicks(timeoutMs: Long, read: () -> T): T? {
        if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
            return null
        }
        try {
            return read()
        } finally {
            lock.unlock()
        }
    }
}
