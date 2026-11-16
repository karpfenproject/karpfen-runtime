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
package io.karpfen.metrics

import io.karpfen.Engine
import io.karpfen.exec.PythonFixtures
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.runtime.TrueParallelFeature
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.metrics.CodeCalls
import io.karpfen.io.karpfen.metrics.CodeWay
import io.karpfen.io.karpfen.metrics.RunMetricsSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The numbers a running engine gives through [Engine.metrics]: one tick per tick and one call per condition and action,
 * counted by the way the code ran, without Python and with it, also while branches run on threads of their own.
 */
class EngineMetricsTest {

    private val engines = ArrayList<Engine>()

    @AfterEach
    fun close() {
        engines.forEach { it.close() }
    }

    @Test
    fun `a condition the runtime computes itself counts once per tick and no time goes to Python`() {
        val numbers = runFor(ticks = 30, stateMachine = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES { INITIAL STATE "on" { } STATE "off" { } }
                TRANSITIONS { TRANSITION "on" -> "off" { CONDITION { EVAL { return $(watts) < 0 } } } }
            }
        """)

        assertEquals(numbers.ticks, numbers.calls.getValue(CodeWay.COMPUTED).count)
        assertEquals(CodeCalls(0, 0), numbers.calls[CodeWay.OPEN_PROCESS])
        assertEquals(CodeCalls(0, 0), numbers.calls[CodeWay.CACHE])
        assertEquals(0, numbers.pythonNanos)
        assertEquals(numbers.tickNanos, numbers.outsidePythonNanos)
        assertTrue(numbers.maxTickNanos > 0)
        assertEquals(minOf(numbers.ticks, 100L).toInt(), numbers.recentTickNanos.size)
    }

    @Test
    @Tag("python")
    fun `code in Python counts in its own way with its time in Python, also the code the runtime hands on`() {
        val numbers = runFor(ticks = 10, stateMachine = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES { INITIAL STATE "on" { } STATE "off" { } }
                TRANSITIONS {
                    TRANSITION "on" -> "off" { CONDITION { EVAL { return $(watts) < 0 } } }
                    TRANSITION "on" -> "off" { CONDITION { EVAL { return abs($(watts)) < 0 } } }
                    TRANSITION "on" -> "off" { CONDITION { EVAL { return 2 } } }
                    TRANSITION "on" -> "off" { CONDITION { EVAL { return @negative($(watts)) } } }
                }
                MACROS {
                    MACRO "negative" {
                        TAKES("value", "number")
                        RETURNS("boolean")
                        DEFINITION { EVAL { return $(value) < 0 } }
                    }
                }
            }
        """)

        val ticks = numbers.ticks
        assertEquals(ticks, numbers.calls.getValue(CodeWay.COMPUTED).count)
        assertEquals(3 * ticks, numbers.calls.getValue(CodeWay.OPEN_PROCESS).count, "abs(), the fallback of 2 and the macro")
        assertEquals(CodeCalls(0, 0), numbers.calls[CodeWay.CACHE])
        assertTrue(numbers.calls.getValue(CodeWay.OPEN_PROCESS).nanos > 0)
        assertTrue(numbers.pythonNanos <= numbers.tickNanos)
        assertEquals(numbers.tickNanos - numbers.pythonNanos, numbers.outsidePythonNanos)
    }

    @Test
    @Tag("python")
    fun `with TrueParallel the calls of all branches count`() {
        val numbers = runFor(ticks = 6, parallel = true, stateMachine = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "init" { }
                    ${(1..4).joinToString("\n") { "STATE \"s$it\" { DO { APPEND(\"log\", EVAL { return \"b\" + str($it) }) } }" }}
                }
                TRANSITIONS { SPLIT "init" -> "s1", "s2", "s3", "s4" { } }
            }
        """)

        val inPython = numbers.calls.getValue(CodeWay.OPEN_PROCESS)
        assertEquals(0L, inPython.count % 4, "every branch calls once per tick")
        assertTrue(inPython.count >= 4 * (numbers.ticks - 1), "four branches in every tick after the split")
        assertTrue(numbers.outsidePythonNanos <= numbers.tickNanos)
    }

    /**
     * Runs [stateMachine] on lamp `a` of the lamp world until it has ticked at least [ticks] times, stops it and
     * answers its numbers, which no longer change after the stop.
     */
    private fun runFor(ticks: Long, stateMachine: String, parallel: Boolean = false): RunMetricsSnapshot {
        val metamodel = PythonFixtures.metamodel()
        val features = FeatureManager()
        if (parallel) features.requestFeatureActivation(TrueParallelFeature::class)
        val engine = Engine(
            metamodel = metamodel,
            model = PythonFixtures.model(metamodel),
            statemachineMap = mapOf("a" to PythonFixtures.stateMachine(stateMachine)),
            tickDelayMS = 1,
            eventBus = EventBus(),
            featureManager = features
        ).also { engines += it }
        engine.start()
        PythonFixtures.await("$ticks ticks") { engine.metrics.snapshot().ticks >= ticks }
        engine.stop()
        return engine.metrics.snapshot().also { assertEquals(it, engine.metrics.snapshot()) }
    }
}
