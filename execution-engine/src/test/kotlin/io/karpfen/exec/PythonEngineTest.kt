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
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.runtime.TrueParallelFeature
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.messages.PayloadFormat
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs code in Python through real engines: what an error in code does to an action, a condition and the trace log,
 * objects that know each other, event values with hard texts, the one Python process that all instances share over
 * many ticks and when it ends, and branches that call Python at the same time with the feature TrueParallel.
 */
@Tag("python")
class PythonEngineTest {

    private val engines = ArrayList<Engine>()
    private val before = PythonFixtures.pythonProcesses()

    @AfterEach
    fun closeEngines() {
        engines.forEach { it.close() }
    }

    /** The Python processes started since the test began that still run. */
    private fun newPythonProcesses(): Set<Long> = PythonFixtures.pythonProcesses() - before

    @Test
    fun `an error in code fails its action with the Python error in the trace, makes its condition false with the error in the trace, and the engine goes on`() {
        val run = run(attached = mapOf("a" to FAILING_LAMP))

        run.awaitLog("a", "reached")

        assertEquals(
            listOf(
                "Error in onEntry for state 'broken': Python error: ZeroDivisionError: division by zero",
                "Error in onEntry for state 'failing': Python error: ValueError: in the open process"
            ),
            run.errors()
        )
        assertEquals(
            "Error in a condition of a transition from 'broken': Python error: ValueError: no",
            run.conditionErrors().first()
        )
        assertEquals("before", run.read { it.getDataObjectById("a").getProp("label").single() })
        assertTrue("wrong" !in run.enteredStates(), "a condition that raises is false")
        assertTrue(run.engine.isTicking)
    }

    @Test
    fun `objects that know each other fail the code that reads them, and the engine goes on`() {
        val run = run(attached = mapOf("a" to LOOPING_LAMP), model = LOOPING_MODEL)

        run.awaitLog("a", "reached")

        assertEquals(
            listOf("Error in onEntry for state 'loopy': Reference loop through object 'b' cannot be handed to code"),
            run.errors()
        )
        assertTrue("wrong" !in run.enteredStates(), "a condition that reads the loop is false")
        assertTrue(run.engine.isTicking)
    }

    @Test
    fun `an event value with hard texts arrives in the code unchanged`() {
        val run = run(attached = mapOf("a" to LISTENING_LAMP))
        run.awaitEntered("waiting")

        PythonFixtures.HARD_TEXTS.forEachIndexed { index, text ->
            val payload = JSONObject().put("text", text).toString()
            run.engine.receiveExternalEvent(Event("t", "say", payload, payloadFormat = PayloadFormat.JSON))
            PythonFixtures.await("the answer to text $index") { run.log("a").size == 2 * (index + 1) }

            assertEquals(listOf(text, text), run.log("a").takeLast(2), "text $index")
        }
    }

    @Test
    fun `all instances share one Python process over many ticks, which ends only when the engine closes`() {
        val run = run(attached = mapOf("room" to DROPPING_ROOM), definitions = listOf(BUSY))

        PythonFixtures.await("thirty calls of every lamp") {
            listOf("a", "b", "c", "spare").all { id -> run.read { it.getDataObjectById(id).getProp("watts").single() as Double } >= 100.0 }
        }
        assertEquals(1, newPythonProcesses().size)
        val ticks = run.engine.tickCount
        PythonFixtures.await("thirty more ticks") { run.engine.tickCount >= ticks + 30 }
        assertEquals(1, newPythonProcesses().size)

        run.engine.receiveExternalEvent(Event("t", "drop"))
        val dropped = run.engine.tickCount
        PythonFixtures.await("ten ticks after the spare lamp left") { run.engine.tickCount >= dropped + 10 }
        assertEquals(1, newPythonProcesses().size)

        run.engine.close()
        PythonFixtures.await("every process ends with the engine") { newPythonProcesses().isEmpty() }
    }

    @Test
    fun `with TrueParallel branches that call Python at the same time each get their own answers`() {
        val run = run(attached = mapOf("a" to SPLITTING_LAMP), parallel = true)

        PythonFixtures.await("two hundred answers") { run.log("a").size >= 200 }
        run.engine.stop()

        val log = run.log("a")
        val branches = (1..4).map { "s$it" }
        assertTrue(log.all { it in branches || it.removePrefix("m") in branches }, "unexpected answers in $log")
        for (branch in branches) {
            val open = log.count { it == branch }
            val direct = log.count { it == "m$branch" }
            assertTrue(open >= 10 && kotlin.math.abs(open - direct) <= 1, "$branch: $open and $direct answers")
        }
        assertTrue(newPythonProcesses().size in 1..4, "${newPythonProcesses().size} open processes")
        run.engine.close()
        PythonFixtures.await("every process ends with the engine") { newPythonProcesses().isEmpty() }
    }

    // ---- Running ------------------------------------------------------------------------------------------------

    private fun run(
        attached: Map<String, String>,
        model: String = PythonFixtures.MODEL.replace(
            "    has(\"lamps\") -> make object \"c\"",
            "    has(\"spare\") -> make object \"spare\":\"Lamp\" { prop(\"watts\") -> \"0\" }\n    has(\"lamps\") -> make object \"c\""
        ),
        definitions: List<String> = emptyList(),
        parallel: Boolean = false
    ): Run {
        val metamodelText = if (definitions.isEmpty()) METAMODEL else METAMODEL.replace("type \"Lamp\" \"A lamp\"", "type \"Lamp\" \"A lamp\" behaves after \"busy\"")
        val metamodel = PythonFixtures.accept(KmetaReader().read(metamodelText))
        val manager = FeatureManager()
        if (parallel) manager.requestFeatureActivation(TrueParallelFeature::class)
        val engineId = "python-${COUNTER.incrementAndGet()}"
        val engine = Engine(
            metamodel = metamodel,
            model = PythonFixtures.accept(KmodelReader(metamodel).read(model)),
            statemachineMap = attached.mapValues { PythonFixtures.stateMachine(it.value) },
            tickDelayMS = 1,
            eventBus = EventBus(),
            engineId = engineId,
            traceLogger = EngineTraceLogger(engineId, consoleOutput = false),
            eventMetamodel = PythonFixtures.accept(KmetaReader().read(EVENTS)),
            featureManager = manager,
            stateMachineDefinitions = definitions.map(PythonFixtures::stateMachine).associateBy { it.name!! }
        ).also { engines += it }
        return Run(engine).also { engine.start() }
    }

    /** A started engine with its trace. */
    private class Run(val engine: Engine) {

        private val entries = CopyOnWriteArrayList<EngineTraceLogger.TraceEntry>()

        init {
            engine.traceLogger!!.addTraceListener { entries += it }
        }

        /** The messages of the action errors so far. */
        fun errors(): List<String> = entries.filter { it.eventType == TraceEventType.ACTION_ERROR }.map { it.message }

        /** The messages of the condition errors so far. */
        fun conditionErrors(): List<String> =
            entries.filter { it.eventType == TraceEventType.CONDITION_ERROR }.map { it.message }

        /** The states whose ENTRY or DO ran so far, by the details of the trace. */
        fun enteredStates(): Set<String> = entries.mapNotNull { it.details["state"] }.toSet()

        fun <T> read(read: (io.karpfen.io.karpfen.exec.ModelQueryProcessor) -> T): T =
            engine.readBetweenTicks(10_000) { read(engine.modelQueryProcessor) }!!

        fun log(id: String): List<String> = read { mqp -> mqp.getDataObjectById(id).getProp("log").map { it.toString() } }

        fun awaitLog(id: String, entry: String) = PythonFixtures.await("the log entry $entry") { entry in log(id) }

        fun awaitEntered(state: String) = PythonFixtures.await("the state $state") { state in enteredStates() }
    }

    private companion object {
        val COUNTER = AtomicInteger()

        val METAMODEL = PythonFixtures.METAMODEL.replace(
            "    has(\"lamps\", list(\"Lamp\"))",
            "    has(\"lamps\", list(\"Lamp\"))\n    has(\"spare\", \"Lamp\")"
        )

        val EVENTS = """
            type "say" "Something to say" {
                prop("text", "string")
            }
        """.trimIndent()

        /** Macros that code calls with `@name`. */
        val MACROS = """
            MACROS {
                MACRO "plus_one" {
                    TAKES("value", "number")
                    RETURNS("number")
                    DEFINITION { EVAL { return $(value) + 1 } }
                }
                MACRO "echo" {
                    TAKES("value", "string")
                    RETURNS("string")
                    DEFINITION { EVAL { return $(value) } }
                }
                MACRO "fail" {
                    RETURNS("number")
                    DEFINITION { EVAL { raise ValueError("in the open process") } }
                }
            }
        """

        val FAILING_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "broken" {
                        ENTRY {
                            SET("label", "before")
                            SET("watts", EVAL { return $(watts) / 0 })
                            SET("label", "after")
                        }
                    }
                    STATE "failing" {
                        ENTRY {
                            SET("watts", EVAL { return @fail() })
                            SET("label", "after")
                        }
                    }
                    STATE "wrong" { ENTRY { APPEND("log", "wrong") } }
                    STATE "next" { ENTRY { APPEND("log", "reached") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "broken" { }
                    TRANSITION "broken" -> "wrong" { CONDITION { EVAL { raise ValueError("no") } } }
                    TRANSITION "broken" -> "failing" { CONDITION { EVAL { return @plus_one($(watts)) > 0 } } }
                    TRANSITION "failing" -> "next" { }
                }
                $MACROS
            }
        """

        val LOOPING_MODEL = PythonFixtures.MODEL
            .replace("prop(\"watts\") -> \"40\"", "prop(\"watts\") -> \"40\"\n        knows(\"partners\") -> \"b\"")
            .replace("prop(\"watts\") -> \"60\"", "prop(\"watts\") -> \"60\"\n        knows(\"partners\") -> \"a\"")

        val LOOPING_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "loopy" { ENTRY { SET("watts", EVAL { return len($(partners)) }) } }
                    STATE "wrong" { ENTRY { APPEND("log", "wrong") } }
                    STATE "next" { ENTRY { APPEND("log", "reached") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "loopy" { }
                    TRANSITION "loopy" -> "wrong" { CONDITION { EVAL { return len($(partners)) > 0 } } }
                    TRANSITION "loopy" -> "next" { }
                }
            }
        """

        val LISTENING_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "waiting" { ENTRY { SET("on", "false") } }
                    STATE "heard" {
                        ENTRY {
                            APPEND("log", EVAL { return $(event->text) })
                            APPEND("log", EVAL { return @echo($(event->text)) })
                        }
                    }
                }
                TRANSITIONS {
                    TRANSITION "waiting" -> "heard" { CONDITION { EVENT("t", "say") } }
                    TRANSITION "heard" -> "waiting" { }
                }
                $MACROS
            }
        """

        /** Every lamp calls Python once per tick. */
        val BUSY = """
            STATEMACHINE "busy" ATTACHABLE TO "Lamp" {
                STATES {
                    INITIAL STATE "working" { DO { SET("watts", EVAL { return @plus_one($(watts)) }) } }
                }
                TRANSITIONS { }
                $MACROS
            }
        """

        val DROPPING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "waiting" { }
                    STATE "dropped" { ENTRY { DROPOBJ("spare") } }
                }
                TRANSITIONS {
                    TRANSITION "waiting" -> "dropped" { CONDITION { EVENT("t", "drop") } }
                }
            }
        """

        /** Splits into four branches that each call Python twice in every tick, with and without a macro. */
        val SPLITTING_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "init" { }
                    ${(1..4).joinToString("\n") { branch(it) }}
                }
                TRANSITIONS {
                    SPLIT "init" -> "s1", "s2", "s3", "s4" { }
                }
                $MACROS
            }
        """

        fun branch(n: Int) = """
            STATE "s$n" {
                DO {
                    APPEND("log", EVAL { return @echo("s$n") })
                    APPEND("log", EVAL { return "m" + "s$n" })
                }
            }
        """
    }
}
