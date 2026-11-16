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
package io.karpfen.benchmark

import io.karpfen.Engine
import io.karpfen.io.karpfen.features.runtime.TickByTickFeature
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.metrics.RunMetricsSnapshot
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Runs an engine a given number of ticks at a time, through what the engine offers to any client: the feature
 * TickByTick. The engine runs on its own thread with a tick delay of 0; the driver pauses it, and each [run] hands it
 * a number of ticks with the message `tick:<n>` and waits until the engine waits again after the last of them.
 *
 * The engine reports each wait between two ticks through [Engine.onEngineStatusChanged] on its own thread, before the
 * next tick begins. The driver uses that report to learn the end of a run, and calls [beforeTick] there with the number
 * of the tick that just ended, so a benchmark can send events or read the numbers of the engine at an exact tick.
 *
 * The pause costs every tick a little: two status texts and a semaphore. That cost is part of the time of an
 * operation of a benchmark, and not part of the durations of ticks in [metrics], which the engine measures inside the
 * tick.
 *
 * @property engine the engine to run; its feature manager must allow TickByTick.
 * @property beforeTick called on the engine thread between two ticks with the number of the tick before.
 */
class TickDriver(
    private val engine: Engine,
    private val beforeTick: (Long) -> Unit = {}
) : AutoCloseable {

    private val done = Semaphore(0)

    /** The tick after which the current run ends, or -1 while none runs. */
    @Volatile
    private var target = -1L

    /**
     * Starts the engine and pauses it. The engine resumes TickByTick when it starts for the first time, so the pause is
     * sent once the first tick has run; the ticks before the pause are not counted by any run.
     */
    fun start() {
        check(engine.featureManager.requestFeatureActivation(TickByTickFeature::class)) { "TickByTick is not on" }
        engine.onEngineStatusChanged = ::reported
        engine.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (engine.tickCount < 1) {
            check(engine.isTicking && System.nanoTime() < deadline) { "The engine did not tick" }
            Thread.onSpinWait()
        }
        target = 0L
        engine.featureManager.onMessage(TickByTickFeature::class, "pause")
        await()
    }

    /** Runs [ticks] ticks and returns once the engine waits after the last of them. */
    fun run(ticks: Int) {
        target = engine.tickCount + ticks
        engine.featureManager.onMessage(TickByTickFeature::class, "tick:$ticks")
        await()
    }

    /** Hands [event] to the engine as an event from outside. */
    fun send(event: Event) = engine.receiveExternalEvent(event)

    /** The numbers of the engine. */
    fun metrics(): RunMetricsSnapshot = engine.metrics.snapshot()

    /** The number of the latest tick. */
    val tickCount: Long
        get() = engine.tickCount

    override fun close() {
        engine.close()
    }

    private fun reported(status: String) {
        if (!status.contains("\"waiting\":true")) return
        val tick = engine.tickCount
        beforeTick(tick)
        val until = target
        if (until >= 0 && tick >= until) {
            target = -1L
            done.release()
        }
    }

    private fun await() {
        check(done.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) { "The engine did not finish its ticks" }
        check(!engine.hasHalted) { "The engine halted" }
    }

    private companion object {
        /** How long a run may take at most; a hung run fails the benchmark instead of hanging it. */
        const val WAIT_SECONDS = 600L
    }
}
