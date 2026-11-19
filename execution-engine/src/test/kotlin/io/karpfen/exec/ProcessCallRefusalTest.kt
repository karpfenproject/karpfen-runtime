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

import io.karpfen.Engine
import io.karpfen.EngineTraceLogger
import io.karpfen.EngineTraceLogger.TraceEventType
import io.karpfen.io.karpfen.exec.CodeOutput
import io.karpfen.io.karpfen.exec.LruResultCache
import io.karpfen.io.karpfen.exec.MacroProcessor
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import io.karpfen.io.karpfen.exec.PythonExecutionException
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.metrics.CodeWay
import io.karpfen.io.karpfen.metrics.RunMetrics
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Refused code as a modeler meets it: an `EVAL`, a macro and a macro that other code calls fail with the refusal, which
 * counts the line within their own code; a model value that holds a refused word does not count; a refused call is not
 * stored in the result cache and leaves the process running; and the refusal reaches the trace log as `ACTION_ERROR` and
 * `CONDITION_ERROR`, while an engine with process calls allowed runs the same code.
 */
@Tag("python")
class ProcessCallRefusalTest {

    private val metrics = RunMetrics()
    private val processors = mutableListOf<MacroProcessor>()
    private val engines = mutableListOf<Engine>()

    @AfterEach
    fun close() {
        processors.forEach { it.pythonSessions.close() }
        engines.forEach { it.close() }
    }

    // ---- EVAL and macros ----

    @Test
    fun `an EVAL that uses a refused form fails with the refusal and the line within its code`() {
        val processor = processor()

        val refused = assertFailsWith<PythonExecutionException> {
            processor.executeInlineMacro("x = 1\nimport subprocess\nreturn x", "number")
        }

        assertEquals(refusal("subprocess", "line 2"), refused.message)
    }

    @Test
    fun `a macro that uses a refused form fails alike, called directly or from other code`() {
        val processor = processor()

        val direct = assertFailsWith<PythonExecutionException> { processor.executeFullMacro("starter") }
        val called = assertFailsWith<PythonExecutionException> {
            processor.executeInlineMacro("return @starter()", "number")
        }
        val nested = assertFailsWith<PythonExecutionException> {
            processor.executeInlineMacro("return @outer()", "number")
        }

        assertEquals(refusal("os.system", "line 2"), direct.message)
        assertEquals(refusal("os.system", "line 2 of macro 'starter'"), called.message)
        assertEquals(refusal("os.system", "line 2 of macro 'starter'"), nested.message)
    }

    @Test
    fun `a model value with a refused word in it is a string and runs`() {
        val processor = processor()
        val text = "import subprocess; os.system('pip install x'); eval(__import__)"
        processor.modelQueryProcessor.setScalar("a", "label", text)

        assertEquals(text, processor.executeInlineMacro("return $(label)", "string"))
        assertEquals("$text!", processor.executeInlineMacro("return f\"{$(label)}!\"", "string"))
    }

    @Test
    fun `a refused call is not stored in the result cache and leaves the process running`() {
        val processor = processor()
        assertEquals(41.0, processor.executeInlineMacro("return abs(-41)", "number"))
        val processes = PythonFixtures.pythonProcesses()

        repeat(2) {
            assertFailsWith<PythonExecutionException> {
                processor.executeInlineMacro("import subprocess\nreturn 1", "number")
            }
        }

        assertEquals(3L, metrics.snapshot().calls.getValue(CodeWay.OPEN_PROCESS).count)
        assertEquals(0L, metrics.snapshot().calls.getValue(CodeWay.CACHE).count)
        assertEquals(processes, PythonFixtures.pythonProcesses())
        assertEquals(42.0, processor.executeInlineMacro("return abs(-42)", "number"))
    }

    // ---- Trace log ----

    @Test
    fun `a refused EVAL in an action is an ACTION_ERROR and one in a condition a CONDITION_ERROR`() {
        val entries = start(LAMP, allowProcessCalls = false)
        PythonFixtures.await("an action error") { entries.any { it.eventType == TraceEventType.ACTION_ERROR } }
        PythonFixtures.await("a condition error") { entries.any { it.eventType == TraceEventType.CONDITION_ERROR } }

        val action = entries.first { it.eventType == TraceEventType.ACTION_ERROR }
        val condition = entries.first { it.eventType == TraceEventType.CONDITION_ERROR }
        assertEquals("Error in onEntry for state 'start': ${refusal("subprocess", "line 1")}", action.message)
        assertEquals(
            "Error in a condition of a transition from 'start': ${refusal("os.system", "line 2")}",
            condition.message
        )
        assertTrue(entries.none { it.details["state"] == "spawned" }, "a refused condition is false")
    }

    @Test
    fun `an engine with process calls allowed runs the same code`() {
        val entries = start(LAMP, allowProcessCalls = true)
        PythonFixtures.await("the state after the condition") {
            entries.any { it.eventType == TraceEventType.STATE_ENTRY_EXEC && it.details["state"] == "spawned" }
        }

        assertTrue(entries.none { it.eventType == TraceEventType.ACTION_ERROR }, "errors: $entries")
        assertTrue(entries.none { it.eventType == TraceEventType.CONDITION_ERROR }, "errors: $entries")
        val label = engines.single().readBetweenTicks(10_000) {
            engines.single().modelQueryProcessor.getDataObjectById("a").getProp("label").single()
        }
        assertEquals("ran", label)
    }

    /** A macro processor on the lamp `a` with the macros of [MACROS] that reports to [metrics] and caches results. */
    private fun processor(): MacroProcessor {
        val metamodel = PythonFixtures.metamodel()
        val model = PythonFixtures.model(metamodel)
        return MacroProcessor(
            metamodel, model, PythonFixtures.stateMachine(MACROS).macros, "a", ModelQueryProcessor(metamodel, model),
            metrics,
            resultCache = LruResultCache(100, 1_000_000),
            output = CodeOutput { _, _ -> }
        ).also { processors += it }
    }

    /** Starts an engine with the state machine [text] on the lamp `a` and gives the list its trace log fills. */
    private fun start(text: String, allowProcessCalls: Boolean): List<EngineTraceLogger.TraceEntry> {
        val metamodel = PythonFixtures.metamodel()
        val entries = CopyOnWriteArrayList<EngineTraceLogger.TraceEntry>()
        val engine = Engine(
            metamodel = metamodel,
            model = PythonFixtures.model(metamodel),
            statemachineMap = mapOf("a" to PythonFixtures.stateMachine(text)),
            tickDelayMS = 1,
            eventBus = EventBus(),
            traceLogger = EngineTraceLogger("process-calls", consoleOutput = false),
            allowProcessCalls = allowProcessCalls
        ).also { engines += it }
        engine.traceLogger!!.addTraceListener { entries += it }
        engine.start()
        return entries
    }

    private companion object {

        /** The refusal of a use of [use] at [place]. */
        fun refusal(use: String, place: String): String =
            "Code may not use '$use' ($place). Process calls are switched off; " +
                "engine.allowProcessCalls = true in application.conf allows them."

        /** `starter` uses `os.system` in its second line, and `outer` calls it. */
        val MACROS = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "start" { }
                }
                TRANSITIONS { }
                MACROS {
                    MACRO "starter" {
                        RETURNS("number")
                        DEFINITION {
                            EVAL {
                                import os
                                return os.system("true")
                            }
                        }
                    }
                    MACRO "outer" {
                        RETURNS("number")
                        DEFINITION { EVAL { return @starter() } }
                    }
                }
            }
        """

        /**
         * Sets the label with code that imports `subprocess` on entry, and leaves `start` for `spawned` only when a
         * condition that calls `os.system` holds.
         */
        val LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "start" {
                        ENTRY {
                            SET("label", EVAL {
                                import subprocess
                                return "ran"
                            })
                        }
                    }
                    STATE "spawned" { ENTRY { APPEND("log", "spawned") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "spawned" {
                        CONDITION {
                            EVAL {
                                import os
                                return os.system("true") == 0
                            }
                        }
                    }
                }
            }
        """
    }
}
