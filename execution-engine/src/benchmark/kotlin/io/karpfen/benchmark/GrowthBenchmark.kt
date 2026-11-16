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
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.Param
import kotlinx.benchmark.Setup
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType

/**
 * Long runs that show whether a tick gets slower the longer the engine runs. Each iteration builds a new engine and
 * runs it once for 1000, 10000 or 100000 ticks; the score is the time of the whole run. Two state machines without
 * Python:
 *
 * - `splitJoin`: a lamp that splits into two branches, lets each take a step and joins them again, in a circle of four
 *   ticks;
 * - `events`: a lamp that gets an event before every tick and switches between two states on it, with the time to live
 *   of the runtime's default configuration, 3000 ms.
 *
 * Before the first run of a trial, a separate engine of the same state machine runs 5000 ticks, so the code of the
 * engine is compiled before the measured runs, without a warm-up iteration of the whole length.
 *
 * The tick metrics line of a trial adds `firstTenthTickMs` and `lastTenthTickMs`: the mean tick duration the engine
 * measured over the first and the last tenth of each measured run.
 */
@BenchmarkMode(Mode.SingleShotTime)
open class GrowthBenchmark : BenchmarkSettings() {

    @Param("splitJoin", "events")
    var machine: String = "splitJoin"

    @Param("1000", "10000", "100000")
    var ticks: Int = 1000

    private lateinit var driver: TickDriver
    private val log = TickMetricsLog()
    @Volatile private var start: RunMetricsSnapshot? = null
    @Volatile private var firstTenth: RunMetricsSnapshot? = null
    @Volatile private var ninthTenth: RunMetricsSnapshot? = null
    @Volatile private var startTick = 0L

    @Setup(Level.Trial)
    fun warmUp() {
        driver = TickDriver(scenario().engine(), ::beforeTick)
        driver.start()
        driver.run(WARM_UP_TICKS)
        driver.close()
    }

    @Setup(Level.Iteration)
    fun startEngine() {
        val scenario = scenario()
        firstTenth = null
        ninthTenth = null
        driver = TickDriver(scenario.engine(), ::beforeTick)
        driver.start()
        startTick = driver.tickCount
        start = driver.metrics()
    }

    private fun scenario(): Scenario = when (machine) {
        "splitJoin" -> Scenario(LampWorld.METAMODEL, LampWorld.MODEL, mapOf("lamp" to SPLIT_JOIN))
        "events" -> Scenario(LampWorld.METAMODEL, LampWorld.MODEL, mapOf("lamp" to SWITCHING), eventTtlMs = EVENT_TTL_MS)
        else -> throw IllegalArgumentException("Unknown machine: $machine")
    }

    /** Sends the event of the switching lamp and takes the numbers after the first and the ninth tenth of the run. */
    private fun beforeTick(tick: Long) {
        if (machine == "events") driver.send(Event("switch", "press"))
        val done = tick - startTick
        if (start == null) return
        if (done == ticks / 10L) firstTenth = driver.metrics()
        if (done == ticks - ticks / 10L) ninthTenth = driver.metrics()
    }

    @TearDown(Level.Iteration)
    fun stopEngine(iteration: IterationParams) {
        val end = driver.metrics()
        driver.close()
        if (iteration.type != IterationType.MEASUREMENT) return
        val begin = checkNotNull(start)
        log.add(begin, end)
        log.extra("firstTenthTickMs", checkNotNull(firstTenth).since(begin).meanTickNanos / 1_000_000.0)
        log.extra("lastTenthTickMs", end.since(checkNotNull(ninthTenth)).meanTickNanos / 1_000_000.0)
        start = null
    }

    @TearDown(Level.Trial)
    fun writeNumbers(params: BenchmarkParams) = log.write(params)

    @Benchmark
    fun run() = driver.run(ticks)

    private companion object {
        /** The ticks of the engine that warms up the code before the measured runs. */
        const val WARM_UP_TICKS = 5000

        /** The default time to live of events in the runtime's configuration. */
        const val EVENT_TTL_MS = 3000L

        val SPLIT_JOIN = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "idle" { }
                    STATE "a1" { }
                    STATE "a2" { }
                    STATE "b1" { }
                    STATE "b2" { }
                }
                TRANSITIONS {
                    SPLIT "idle" -> "a1", "b1" { }
                    TRANSITION "a1" -> "a2" { }
                    TRANSITION "b1" -> "b2" { }
                    JOIN "a2", "b2" -> "idle" { }
                }
            }
        """

        val SWITCHING = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    STATE "on" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "on" { CONDITION { EVENT("switch", "press") } }
                    TRANSITION "on" -> "off" { CONDITION { EVENT("switch", "press") } }
                }
            }
        """
    }
}
