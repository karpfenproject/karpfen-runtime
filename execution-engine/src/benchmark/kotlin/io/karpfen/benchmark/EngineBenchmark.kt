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

import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.metrics.RunMetricsSnapshot
import kotlinx.benchmark.Setup
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType

/**
 * A benchmark of one engine that runs through a whole trial: the engine is built and started once per trial, and every
 * operation runs a fixed number of ticks on it, so the engine reaches its steady state during the warm-up and the
 * measured ticks continue where the warm-up ended. A subclass gives the [scenario] and a benchmark method that calls
 * [ticks] with the number its `@OperationsPerInvocation` declares, so the score is the time of one tick.
 *
 * The numbers the engine measured in the measured iterations go to the [TickMetricsLog] at the end of the trial.
 */
abstract class EngineBenchmark : BenchmarkSettings() {

    private lateinit var driver: TickDriver
    private val log = TickMetricsLog()
    private lateinit var iterationStart: RunMetricsSnapshot

    /** What the engine of this benchmark runs; read at the start of each trial, after the parameters are set. */
    protected abstract fun scenario(): Scenario

    /** Called on the engine thread between two ticks with the number of the tick before; sends nothing by default. */
    protected open fun beforeTick(tick: Long, driver: TickDriver) = Unit

    @Setup(Level.Trial)
    fun startEngine() {
        val engine = scenario().engine()
        driver = TickDriver(engine) { tick -> beforeTick(tick, driver) }
        driver.start()
    }

    @Setup(Level.Iteration)
    fun startIteration() {
        iterationStart = driver.metrics()
    }

    @TearDown(Level.Iteration)
    fun endIteration(iteration: IterationParams) {
        if (iteration.type == IterationType.MEASUREMENT) log.add(iterationStart, driver.metrics())
    }

    @TearDown(Level.Trial)
    fun stopEngine(params: BenchmarkParams) {
        log.write(params)
        driver.close()
    }

    /** Hands [event] to the engine as an event from outside. */
    protected fun send(event: Event) = driver.send(event)

    /** Runs [count] ticks of the engine. */
    protected fun ticks(count: Int) = driver.run(count)
}
