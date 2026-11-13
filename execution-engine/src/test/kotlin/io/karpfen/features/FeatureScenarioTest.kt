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
package io.karpfen.features

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import dsl.textual.KstatesDSLConverter
import io.karpfen.Engine
import io.karpfen.EngineTraceLogger
import io.karpfen.EngineTraceLogger.TraceEventType
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.language.HistoryFeature
import io.karpfen.io.karpfen.features.language.TickLimitFeature
import io.karpfen.io.karpfen.features.runtime.BreakpointFeature
import io.karpfen.io.karpfen.features.runtime.TickByTickFeature
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs small state machines on a real engine with features switched on and checks, tick by tick, which states were
 * active. The state machines need neither events nor Python: their transitions are unconditional, so every run takes
 * the same course, and the checks read the engine's trace, which names the active stack at the end of each tick.
 *
 * The scenarios cover the history of a state that is entered again, the delay and the timeout of a tick limit, an
 * engine that is halted at a breakpoint and tells where, and the status reports of an engine that waits and goes on.
 */
class FeatureScenarioTest {

    /** The engines the scenarios started, stopped after each test. */
    private val engines = ArrayList<Engine>()

    @AfterEach
    fun stopEngines() {
        engines.forEach { it.stop() }
    }

    /**
     * A shallow history remembers the directly nested state, also when the state machine went straight to a state
     * nested deeper: coming back to "outer" continues in "middle", not in "inner".
     */
    @Test
    fun `a shallow history leads back to the directly nested state`() {
        val run = run(historyMachine("SHALLOW HISTORY "), HistoryFeature::class)

        run.awaitTick(6)

        assertEquals(
            listOf("outer,middle,inner", "away", "outer", "outer,middle", "outer,middle", "outer,middle"),
            run.stacksUpTo(6)
        )
    }

    /**
     * A deep history remembers the innermost state: coming back to "outer" continues in "inner".
     */
    @Test
    fun `a deep history leads back to the innermost state`() {
        val run = run(historyMachine("DEEP HISTORY "), HistoryFeature::class)

        run.awaitTick(5)

        assertEquals(listOf("outer,middle,inner", "away", "outer", "outer,middle,inner", "away"), run.stacksUpTo(5))
    }

    /**
     * Without the feature a history changes nothing: the state machine stays in "outer".
     */
    @Test
    fun `a history is ignored while the feature is off`() {
        val run = run(historyMachine("DEEP HISTORY "))

        run.awaitTick(5)

        assertEquals(listOf("outer,middle,inner", "away", "outer", "outer", "outer"), run.stacksUpTo(5))
    }

    /**
     * The entry of the state with the history runs before the history leads on, and the remembered state is entered
     * like any other. "inner" is left only once its own entry has set the flag.
     */
    @Test
    fun `the state with the history is entered before its remembered state`() {
        val machine = machine(
            """
            INITIAL STATE "start" { }
            DEEP HISTORY STATE "outer" {
                ENTRY { APPEND("log", "outer") }
                STATE "inner" { ENTRY { SET("flag", "true") } }
            }
            STATE "away" { ENTRY { SET("flag", "false") } }
            """,
            """
            TRANSITION "start" -> "inner" { }
            TRANSITION "inner" -> "away" { CONDITION { VALUE("flag") } }
            TRANSITION "away" -> "outer" { }
            """
        )
        val run = run(machine, HistoryFeature::class)

        run.awaitTick(6)

        assertEquals(
            listOf("outer", "inner", "outer", "inner"),
            run.traces(TraceEventType.STATE_ENTRY_EXEC).map { it.details.getValue("state") }.filter { it != "away" }.take(4)
        )
    }

    /**
     * A delay holds back only the transitions that leave its state. "outer" must stay for more than five ticks, but
     * its nested states change at once, and "outer" is left in the first tick after the delay.
     */
    @Test
    fun `a delay holds back only the transitions that leave its state`() {
        val machine = machine(
            """
            INITIAL STATE "outer" {
                TICK LIMIT { DELAY: 5 TICKS }
                STATE "one" { }
                STATE "two" { }
            }
            STATE "done" { }
            """,
            """
            TRANSITION "one" -> "two" { }
            TRANSITION "two" -> "done" { }
            """
        )
        val run = run(machine, TickLimitFeature::class)

        run.awaitTick(7)

        assertEquals(
            listOf("outer,two", "outer,two", "outer,two", "outer,two", "outer,two", "done", "done"),
            run.stacksUpTo(7)
        )
    }

    /**
     * A timeout that leads back to its own state counts anew after each entry: the state is entered again in every
     * fourth tick, not in every tick after the first timeout.
     */
    @Test
    fun `a state entered again counts its ticks anew`() {
        val machine = machine(
            """
            INITIAL STATE "a" {
                TICK LIMIT { TIMEOUT: AFTER 3 TICKS TRANSITION TO "a" }
            }
            """,
            ""
        )
        val run = run(machine, TickLimitFeature::class)

        run.awaitTick(13)

        assertEquals(listOf(4, 8, 12), run.traces(TraceEventType.TRANSITION_FIRED).map { run.tickOf(it) })
    }

    /**
     * A timeout fires in the first tick after its ticks, and the delay of the state it leaves comes first.
     */
    @Test
    fun `a timeout waits for the delay of its state`() {
        val machine = machine(
            """
            INITIAL STATE "a" {
                TICK LIMIT { DELAY: 5 TICKS TIMEOUT: AFTER 2 TICKS TRANSITION TO "b" }
            }
            STATE "b" { }
            """,
            ""
        )
        val run = run(machine, TickLimitFeature::class)

        run.awaitTick(7)

        assertEquals(listOf("a", "a", "a", "a", "a", "b", "b"), run.stacksUpTo(7))
    }

    /**
     * An engine halted at the entry of a state leaves the model readable, goes on when it is resumed, and halts again
     * at the next breakpoint.
     */
    @Test
    fun `an engine halted at a breakpoint can be read and resumed`() {
        val machine = machine(
            """
            INITIAL STATE "a" { }
            STATE "b" { BREAKPOINT { ENTRY } }
            STATE "c" { BREAKPOINT { ENTRY } }
            """,
            """
            TRANSITION "a" -> "b" { }
            TRANSITION "b" -> "c" { }
            """
        )
        val run = run(machine, BreakpointFeature::class)

        run.awaitTickStart(2)
        assertEquals("read", run.engine.readBetweenTicks(READ_TIMEOUT_MS) { "read" }, "the halted engine keeps its tick gate")
        assertEquals(listOf("b"), run.stacksUpTo(1))

        run.manager.onMessage(TickByTickFeature::class, "resume")
        run.awaitTickStart(3)
        assertEquals("read", run.engine.readBetweenTicks(READ_TIMEOUT_MS) { "read" })
        assertEquals(listOf("b", "c"), run.stacksUpTo(2))

        run.manager.onMessage(TickByTickFeature::class, "resume")
        run.awaitTick(4)
        assertEquals("c", run.stacksUpTo(4).last())
    }

    /**
     * A halted engine tells where it waits: at the entry of a state, then at the start and the end of a transition,
     * each with what a debugger needs to find the place. After the last resume it waits nowhere.
     */
    @Test
    fun `a halted engine tells where it waits`() {
        val machine = machine(
            """
            INITIAL STATE "a" { }
            STATE "b" { BREAKPOINT { ENTRY } }
            STATE "c" { }
            """,
            """
            TRANSITION "a" -> "b" { }
            TRANSITION "b" -> "c" { BREAKPOINT { START END } }
            """
        )
        val run = run(machine, BreakpointFeature::class)

        val entry = run.awaitHalt()
        assertEquals(listOf(OBJECT_ID, "entry", "b"), listOf("modelElementId", "timing", "state").map { entry.getString(it) })

        run.manager.onMessage(TickByTickFeature::class, "resume")
        val start = run.awaitHalt { it.optString("timing") == "start" }
        assertEquals(listOf("TRANSITION", "[\"b\"]", "[\"c\"]", "1"), listOf("kind", "from", "to", "index").map { start.get(it).toString() })
        assertEquals(listOf("b"), run.stacksUpTo(2).takeLast(1))

        run.manager.onMessage(TickByTickFeature::class, "resume")
        val end = run.awaitHalt { it.optString("timing") == "end" }
        assertEquals(start.getString("transition"), end.getString("transition"))

        run.manager.onMessage(TickByTickFeature::class, "resume")
        run.awaitTick(5)
        val status = run.status()
        assertEquals(listOf(false, false, false), listOf(status.getBoolean("paused"), status.getBoolean("waiting"), status.has("halt")))
    }

    /**
     * Stopping an engine that is halted at a breakpoint ends its thread.
     */
    @Test
    fun `a halted engine can be stopped`() {
        val machine = machine(
            """
            INITIAL STATE "a" { }
            STATE "b" { BREAKPOINT { ENTRY } }
            """,
            """
            TRANSITION "a" -> "b" { }
            """
        )
        val run = run(machine, BreakpointFeature::class)
        run.awaitTickStart(2)
        assertNotNull(run.engine.readBetweenTicks(READ_TIMEOUT_MS) { })

        run.engine.stop()

        assertFalse(run.engine.isTicking)
        assertFalse(run.engine.hasHalted)
    }

    /**
     * A pause between two ticks is reported on the engine thread once it waits and once it goes on, each report with
     * the status at that moment, and a running engine reports nothing in its ticks.
     */
    @Test
    fun `a pause between two ticks is reported when the engine waits and when it goes on`() {
        val run = run(machine("INITIAL STATE \"a\" { }", ""), TickByTickFeature::class)
        run.awaitTick(1)

        run.manager.onMessage(TickByTickFeature::class, "pause")
        run.awaitReports(1)
        assertTrue(run.reports.single().similar(run.pauseStatus()), "the waiting engine reports its status as it is")
        run.manager.onMessage(TickByTickFeature::class, "resume")
        run.awaitReports(2)
        run.awaitTick(run.ticksEnded() + 3)

        assertEquals(listOf(WAITING, RUNNING), run.reportSummaries())
        assertEquals(listOf("Engine-scenario"), run.reportThreads.distinct())
    }

    /**
     * Ticks given to a paused engine are reported tick by tick: the engine goes on, waits at the top of the next tick
     * only as long as it takes to take the permit, and waits again once the ticks are used up. A resume ends the pause.
     */
    @Test
    fun `ticks given to a paused engine are reported as it goes on and waits again`() {
        val run = run(machine("INITIAL STATE \"a\" { }", ""), TickByTickFeature::class)
        run.awaitTick(1)
        run.manager.onMessage(TickByTickFeature::class, "pause")
        run.awaitReports(1)

        run.manager.onMessage(TickByTickFeature::class, "tick:2")
        run.awaitReports(5)
        assertTrue(run.pauseStatus().getBoolean("waiting"))
        run.manager.onMessage(TickByTickFeature::class, "resume")
        run.awaitReports(6)

        assertEquals(
            listOf(WAITING, PAUSED_GOING_ON, WAITING, PAUSED_GOING_ON, WAITING, RUNNING),
            run.reportSummaries()
        )
    }

    /**
     * Each breakpoint is reported with its halt once the engine waits there, the same status that getStatus answers,
     * and the resume that follows is reported without it.
     */
    @Test
    fun `a halt at a breakpoint is reported with where the engine waits`() {
        val machine = machine(
            """
            INITIAL STATE "a" { }
            STATE "b" { BREAKPOINT { ENTRY } }
            STATE "c" { }
            """,
            """
            TRANSITION "a" -> "b" { }
            TRANSITION "b" -> "c" { BREAKPOINT { START END } }
            """
        )
        val run = run(machine, BreakpointFeature::class)

        for (count in listOf(1, 3, 5)) {
            run.awaitReports(count)
            assertTrue(run.reports[count - 1].similar(run.status()), "report $count is what getStatus answers")
            run.manager.onMessage(TickByTickFeature::class, "resume")
            run.awaitReports(count + 1)
        }
        run.awaitTick(5)

        assertEquals(
            listOf(
                listOf(true, true, "entry"), RUNNING,
                listOf(true, true, "start"), RUNNING,
                listOf(true, true, "end"), RUNNING
            ),
            run.reportSummaries()
        )
        assertEquals(OBJECT_ID, run.reports[0].getJSONObject("halt").getString("modelElementId"))
        assertEquals("b", run.reports[0].getJSONObject("halt").getString("state"))
    }

    /** Without TickByTick and Breakpoint the engine never reports a status. */
    @Test
    fun `an engine without the pause features reports no status`() {
        val run = run(historyMachine("DEEP HISTORY "), HistoryFeature::class)

        run.awaitTick(5)

        assertEquals(emptyList(), run.reports.toList())
    }

    /** The state machine of the history scenarios, whose state "outer" is declared with [history] in front. */
    private fun historyMachine(history: String): String = machine(
        """
        INITIAL STATE "start" { }
        ${history}STATE "outer" {
            STATE "middle" {
                STATE "inner" { }
            }
        }
        STATE "away" { }
        """,
        """
        TRANSITION "start" -> "inner" { }
        TRANSITION "inner" -> "away" { }
        TRANSITION "away" -> "outer" { }
        """
    )

    /** A state machine of the scenario object with [states] and [transitions]. */
    private fun machine(states: String, transitions: String): String =
        "STATEMACHINE ATTACHED TO \"Scenario\" {\n    STATES {\n$states\n    }\n    TRANSITIONS {\n$transitions\n    }\n}\n"

    /** Starts an engine that runs [machine] on the scenario object with [features] switched on. */
    private fun run(machine: String, vararg features: KClass<out Feature>): Run {
        val manager = FeatureManager()
        features.forEach { manager.requestFeatureActivation(it) }
        val metamodel = KmetaDSLConverter.parseKmetaString(METAMODEL)
        val engine = Engine(
            metamodel = metamodel,
            model = KmodelDSLConverter.parseKmodelString(MODEL, metamodel),
            statemachineMap = mapOf(OBJECT_ID to KstatesDSLConverter.parseKstatesString(machine)),
            tickDelayMS = 1,
            engineId = "scenario",
            traceLogger = EngineTraceLogger("scenario", consoleOutput = false),
            featureManager = manager
        )
        engines += engine
        return Run(engine, manager).also { engine.start() }
    }

    /**
     * A running engine and what its trace tells.
     *
     * @property engine the engine.
     * @property manager the features of the engine.
     */
    private class Run(val engine: Engine, val manager: FeatureManager) {

        /** Every trace entry of the engine so far, in the order it was written. */
        private val entries = CopyOnWriteArrayList<EngineTraceLogger.TraceEntry>()

        /** Every status the engine reported through [Engine.onEngineStatusChanged] so far, in order. */
        val reports = CopyOnWriteArrayList<JSONObject>()

        /** The name of the thread each of the [reports] arrived on, in the same order. */
        val reportThreads = CopyOnWriteArrayList<String>()

        init {
            engine.traceLogger!!.addTraceListener { entries += it }
            engine.onEngineStatusChanged = { status ->
                reportThreads += Thread.currentThread().name
                reports += JSONObject(status)
            }
        }

        /** Each of the [reports] as `paused`, `waiting` and the timing of its halt, or null without one. */
        fun reportSummaries(): List<List<Any?>> = reports.map {
            listOf(it.getBoolean("paused"), it.getBoolean("waiting"), it.optJSONObject("halt")?.getString("timing"))
        }

        /** Waits until the engine reported [count] statuses. */
        fun awaitReports(count: Int) = await("$count status reports") { reports.size >= count }

        /** The number of ticks that have ended so far. */
        fun ticksEnded(): Int = traces(TraceEventType.TICK_END).size

        /** The trace entries of [type] so far, in order. */
        fun traces(type: TraceEventType): List<EngineTraceLogger.TraceEntry> = entries.filter { it.eventType == type }

        /** What the breakpoint feature answers when it is asked for its status. */
        fun status(): JSONObject = JSONObject(manager.onMessage(BreakpointFeature::class, "{\"action\": \"getStatus\"}"))

        /** The status of the engine as the pause of the tick-by-tick feature describes it. */
        fun pauseStatus(): JSONObject = JSONObject(manager.getActiveFeature<TickByTickFeature>().engineStatus())

        /** Waits until the engine waits at a breakpoint that [accept] accepts, and returns that halt. */
        fun awaitHalt(accept: (JSONObject) -> Boolean = { true }): JSONObject {
            var halt: JSONObject? = null
            await("a halt") {
                val status = status()
                halt = status.optJSONObject("halt")?.takeIf { status.getBoolean("waiting") && accept(it) }
                halt != null
            }
            return halt!!
        }

        /** Waits until the tick [tick] has ended. */
        fun awaitTick(tick: Int) = await("the end of tick $tick") { traces(TraceEventType.TICK_END).any { numberOf(it) == tick } }

        /** Waits until the tick [tick] has begun. */
        fun awaitTickStart(tick: Int) = await("the start of tick $tick") { traces(TraceEventType.TICK_START).any { numberOf(it) == tick } }

        /** The active stack at the end of each of the ticks 1 to [tick], in order. */
        fun stacksUpTo(tick: Int): List<String> {
            val ends = traces(TraceEventType.TICK_END).associate { numberOf(it) to it.details.getValue("stack") }
            return (1..tick).mapNotNull { ends[it] }
        }

        /** The number of the tick during which [entry] was written. */
        fun tickOf(entry: EngineTraceLogger.TraceEntry): Int {
            val all = entries.toList()
            val before = all.subList(0, all.indexOfFirst { it === entry })
            return numberOf(before.last { it.eventType == TraceEventType.TICK_START })
        }

        /** The tick number in the message of a tick's start or end entry. */
        private fun numberOf(entry: EngineTraceLogger.TraceEntry): Int =
            TICK_NUMBER.find(entry.message)!!.groupValues[1].toInt()

        /** Waits until [reached] holds, and fails when it does not within [AWAIT_TIMEOUT_MS]. */
        private fun await(what: String, reached: () -> Boolean) {
            val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
            while (!reached()) {
                if (System.currentTimeMillis() > deadline) fail("The engine did not reach $what")
                Thread.sleep(2)
            }
        }
    }

    private companion object {
        /** The id of the object the state machines are attached to. */
        const val OBJECT_ID = "scenario"

        /** How long a scenario waits for a tick before it fails. */
        const val AWAIT_TIMEOUT_MS = 10_000L

        /** How long a read waits for the tick gate. */
        const val READ_TIMEOUT_MS = 5_000L

        /** The summary of a report from an engine that waits between two ticks. */
        val WAITING = listOf(true, true, null)

        /** The summary of a report from a paused engine that goes on for a tick it was given. */
        val PAUSED_GOING_ON = listOf(true, false, null)

        /** The summary of a report from an engine that runs freely again. */
        val RUNNING = listOf(false, false, null)

        /** Finds the number in "Tick #3". */
        val TICK_NUMBER = Regex("#(\\d+)")

        /** The type of the scenario object. */
        val METAMODEL = """
            type "Scenario" "The object the scenarios run on" {
                prop("flag", "boolean")
                prop("log", list("string"))
            }
        """.trimIndent()

        /** The scenario object. */
        val MODEL = """
            make object "$OBJECT_ID":"Scenario" {
                prop("flag") -> "false"
            }
        """.trimIndent()
    }
}
