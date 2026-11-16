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
import io.karpfen.dsl.composition.FlatteningResult
import io.karpfen.dsl.composition.StateMachineFlattener
import io.karpfen.dsl.reader.ParseResult
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.dsl.states.StateMachine
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.language.HistoryFeature
import io.karpfen.io.karpfen.features.language.TickLimitFeature
import io.karpfen.io.karpfen.features.runtime.TrueParallelFeature
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs flattened state machines with container states, fragments, final states and `DONE` on a real engine: going down
 * into a container state on every way a state is entered, nested containers, a fragment used twice, a container inside
 * a compound state, histories on a container, leaving a container, resting on a final state, `DONE` on a transition, a
 * split and a join, also with TrueParallel on, the qualified names in the trace log and in the reported stacks, and
 * attachable definitions with container states.
 *
 * The state machines are read with the reader of dsl-tools and flattened with its flattener, as the runtime does when an
 * environment is activated. They move on events the test sends once the engine rests where the test expects it, so a
 * run takes the same course every time. The tests wait for reported stacks with generous deadlines, never for a fixed
 * time, and use no Python except the one test with the tag `python`.
 */
class StateMachinePagesEngineTest {

    /** The engines the tests started, closed after each test. */
    private val engines = ArrayList<Engine>()

    @AfterEach
    fun closeEngines() {
        engines.forEach { it.close() }
    }

    @Test
    fun `a transition into a container state goes down to the initial state of its fragment and DONE leaves it`() {
        val run = run(SELF_TEST_LAMP, BULB_TEST)

        run.awaitStack("off")
        run.send("test")
        run.awaitStack("self test", "self test/lit")
        run.awaitLog("lit")
        run.send("next")
        run.awaitStack("self test", "self test/dark")
        run.send("next")
        run.awaitStack("on")

        assertEquals(
            listOf(
                listOf("off"),
                listOf("self test", "self test/lit"),
                listOf("self test", "self test/dark"),
                listOf("self test", "self test/passed"),
                listOf("on")
            ),
            run.stacks()
        )
        val fired = run.traces(TraceEventType.TRANSITION_FIRED).map { "${it.details["from"]} -> ${it.details["to"]} | ${it.details["stack"]}" }
        assertEquals(
            listOf(
                "off -> self test | self test,self test/lit",
                "self test/lit -> self test/dark | self test,self test/dark",
                "self test/dark -> self test/passed | self test,self test/passed",
                "self test -> on | on"
            ),
            fired
        )
        assertEquals(listOf("lit", "dark"), run.log())
    }

    @Test
    fun `an instance that starts in a container state starts inside it`() {
        val run = run(SELF_TEST_LAMP.replace("INITIAL STATE \"off\"", "STATE \"off\"")
            .replace("CONTAINER STATE \"self test\"", "INITIAL CONTAINER STATE \"self test\""), BULB_TEST)

        run.awaitLog("lit")

        assertEquals(listOf("self test", "self test/lit"), run.stacks().first())
        assertEquals(
            "Initial state stack: self test > self test/lit",
            run.traces(TraceEventType.INITIAL_STATE).single().message
        )
    }

    @Test
    fun `a branch rests on a final state while no transition with DONE leaves the container`() {
        val run = run(SELF_TEST_LAMP.replace("TRANSITION \"self test\" -> \"on\" { CONDITION { DONE } }", ""), BULB_TEST)

        run.awaitStack("off")
        run.send("test")
        run.awaitStack("self test", "self test/lit")
        run.send("next")
        run.awaitStack("self test", "self test/dark")
        run.send("next")
        run.awaitStack("self test", "self test/passed")
        val reached = run.ticks()
        run.await("five more ticks") { run.ticks() >= reached + 5 }

        assertEquals(listOf("self test", "self test/passed"), run.stacks().last())
        assertEquals(listOf("self test,self test/passed"), run.tickEndStacks().takeLast(5).distinct())
    }

    @Test
    fun `a transition of the container state leaves it from any inner state`() {
        val run = run(SELF_TEST_LAMP, BULB_TEST)

        run.awaitStack("off")
        run.send("test")
        run.awaitStack("self test", "self test/lit")
        run.send("abort")
        run.awaitStack("off")
        run.send("test")
        run.awaitStack("self test", "self test/lit")
        run.send("next")
        run.awaitStack("self test", "self test/dark")
        run.send("abort")
        run.awaitStack("off")

        assertEquals(
            listOf(
                listOf("off"),
                listOf("self test", "self test/lit"),
                listOf("off"),
                listOf("self test", "self test/lit"),
                listOf("self test", "self test/dark"),
                listOf("off")
            ),
            run.stacks()
        )
    }

    @Test
    fun `nested containers three levels deep go down to the innermost initial state and DONE climbs back out`() {
        val run = run(NESTED_LAMP, OUTER_PAGE, MIDDLE_PAGE, INNER_PAGE)

        run.awaitStack("off")
        run.send("test")
        run.awaitStack("top", "top/middle", "top/middle/inner", "top/middle/inner/b")
        run.send("next")
        run.awaitStack("on")

        assertEquals(
            listOf(
                listOf("off"),
                listOf("top", "top/middle", "top/middle/inner", "top/middle/inner/b"),
                listOf("top", "top/middle", "top/middle/inner", "top/middle/inner/end"),
                listOf("top", "top/middle", "top/middle/end"),
                listOf("top", "top/end"),
                listOf("on")
            ),
            run.stacks()
        )
    }

    @Test
    fun `one fragment used by two container states runs as two independent copies`() {
        val run = run(TWO_PAGES_LAMP, BULB_TEST)

        run.awaitStack("off")
        run.send("test")
        run.awaitStack("one", "one/lit")
        run.send("next")
        run.awaitStack("one", "one/dark")
        run.send("swap")
        run.awaitStack("two", "two/lit")
        run.send("swap")
        run.awaitStack("one", "one/lit")
        run.await("the second entry of one/lit") { run.log().size == 4 }

        assertEquals(listOf("lit", "dark", "lit", "lit"), run.log())
    }

    @Test
    fun `a container state inside a compound state is gone down into, while the compound state is entered as itself`() {
        val run = run(COMPOUND_LAMP, BULB_TEST)

        run.awaitStack("off")
        run.send("test")
        run.awaitStack("check")
        run.send("go")
        run.awaitStack("check", "page", "page/lit")
        run.send("abort")
        run.awaitStack("off")
        run.send("direct")
        run.awaitStack("check", "page", "page/lit")
    }

    @Test
    fun `a container state with a shallow history goes back to the remembered state right inside it`() {
        val run = run(historyLamp("SHALLOW"), STEPS, features = arrayOf(HistoryFeature::class))

        enterStepsAndLeave(run)
        run.send("test")
        run.awaitStack("page", "page/second")

        assertEquals(listOf("page", "page/second"), run.stacks().last())
    }

    @Test
    fun `a container state with a deep history goes back to the remembered innermost state`() {
        val run = run(historyLamp("DEEP"), STEPS, features = arrayOf(HistoryFeature::class))

        enterStepsAndLeave(run)
        run.send("test")
        run.awaitStack("page", "page/second", "page/high")

        assertFalse(listOf("page") in run.stacks(), "the branch never rests on the container state alone")
    }

    @Test
    fun `without the feature History a container state with a history starts its fragment again`() {
        val run = run(historyLamp("DEEP"), STEPS)

        enterStepsAndLeave(run)
        run.send("test")
        run.awaitStack("page", "page/first")
    }

    @Test
    fun `DONE on a split fires once the container state rests on a final state`() {
        val run = run(SPLITTING_LAMP, QUICK)

        run.awaitStack("bulb", "socket")

        assertEquals(
            listOf(listOf("c", "c/done"), listOf("bulb", "socket")),
            run.stacks()
        )
    }

    @Test
    fun `DONE on a join waits until every container source rests on a final state`() {
        joinOverTwoPages(emptyArray())
    }

    @Test
    fun `with TrueParallel a split into two container states goes down into both and DONE on the join merges them`() {
        joinOverTwoPages(arrayOf(TrueParallelFeature::class))
    }

    @Test
    fun `the timeout of a tick limit into a container state goes down into it`() {
        val timed = SELF_TEST_LAMP.replace(
            "INITIAL STATE \"off\" { }",
            "INITIAL STATE \"off\" { TICK LIMIT { TIMEOUT: AFTER 2 TICKS TRANSITION TO \"self test\" } }"
        )
        val run = run(timed, BULB_TEST, features = arrayOf(TickLimitFeature::class))

        run.awaitStack("self test", "self test/lit")

        assertEquals(listOf(listOf("off"), listOf("self test", "self test/lit")), run.stacks())
    }

    @Test
    fun `setActiveState on a container state goes down into it and an inner state takes its qualified name`() {
        val run = run(SELF_TEST_LAMP, BULB_TEST)

        run.awaitStack("off")
        run.engine.forceActiveState(LAMP, "self test")
        run.awaitStack("self test", "self test/lit")
        run.engine.forceActiveState(LAMP, "self test/dark")
        run.awaitStack("self test", "self test/dark")
        val reached = run.ticks()
        run.await("two more ticks") { run.ticks() >= reached + 2 }

        assertEquals(emptyList(), run.log(), "a forced state runs no ENTRY block")

        val forced = run.traces(TraceEventType.INITIAL_STATE).map { it.message }
        assertEquals(
            listOf(
                "Initial state stack: off",
                "Forced state: self test > self test/lit",
                "Forced state: self test > self test/dark"
            ),
            forced
        )
    }

    @Test
    fun `the mark of a fragment is not seen from the state machine that uses it`() {
        val run = run(HIDDEN_MARK_LAMP, LEVELS)

        run.awaitStack("spare", "ready")

        assertEquals(listOf("spare", "ready"), run.stacks().first())
    }

    @Test
    fun `a marked container state starts in the marked state of its fragment`() {
        val marked = HIDDEN_MARK_LAMP.replace("CONTAINER STATE \"on\"", "INITIAL CONTAINER STATE \"on\"")
            .replace("INITIAL STATE \"ready\"", "STATE \"ready\"")
        val run = run(marked, LEVELS)

        run.awaitStack("on", "on/dimmed")

        assertEquals(listOf("on", "on/dimmed"), run.stacks().first())
    }

    @Test
    fun `a transition from a container state to itself enters the container and the start of its fragment again`() {
        val again = SELF_TEST_LAMP.replace(
            "TRANSITION \"self test\" -> \"off\"",
            "TRANSITION \"self test\" -> \"self test\" { CONDITION { EVENT(\"t\", \"again\") } }\n" +
                "                    TRANSITION \"self test\" -> \"off\""
        )
        val run = run(again, BULB_TEST)

        run.awaitStack("off")
        run.send("test")
        run.awaitStack("self test", "self test/lit")
        run.send("next")
        run.awaitStack("self test", "self test/dark")
        run.send("again")
        run.awaitStack("self test", "self test/lit")
        run.await("the second entry of self test/lit") { run.log().size == 3 }

        assertEquals(listOf("lit", "dark", "lit"), run.log())
        val entered = run.traces(TraceEventType.STATE_ENTRY_SKIP).map { it.details["state"] }
        assertEquals(2, entered.count { it == "self test" }, "the container state is entered again")
    }

    @Test
    fun `the same behaviour as a compound state and as a container state gives the same ticks with other names`() {
        val compound = run(COMPOUND_SELF_TEST)
        val container = run(CONTAINER_SELF_TEST, RUNNING_TEST)

        compound.awaitLog("on")
        container.awaitLog("on")

        assertEquals(
            listOf("self test,lit", "self test,dark", "self test,passed", "on"),
            compound.tickEndStacks().take(4)
        )
        assertEquals(
            listOf("self test,self test/lit", "self test,self test/dark", "self test,self test/passed", "on"),
            container.tickEndStacks().take(4)
        )
        assertEquals(compound.entryTicks(), container.entryTicks())
        assertEquals(listOf(2, 3, 5), compound.entryTicks())
    }

    @Test
    fun `a transition into a compound state rests on it and enters none of its inner states`() {
        val run = run(COMPOUND_SELF_TEST.replace("\"off\" -> \"lit\"", "\"off\" -> \"self test\""))

        run.awaitStack("self test")
        val reached = run.ticks()
        run.await("three more ticks") { run.ticks() >= reached + 3 }

        assertEquals(listOf(listOf("self test")), run.stacks())
        assertEquals(emptyList(), run.log())
    }

    @Test
    fun `a compound state with a history rests on itself for one tick before it leads on to the remembered state`() {
        val run = run(COMPOUND_HISTORY_LAMP, features = arrayOf(HistoryFeature::class))

        run.awaitStack("off")
        run.send("test")
        run.awaitStack("c", "a")
        run.send("next")
        run.awaitStack("c", "b")
        run.send("abort")
        run.awaitStack("off")
        run.send("back")
        run.awaitStack("c", "b")

        assertEquals(listOf(listOf("off"), listOf("c"), listOf("c", "b")), run.stacks().takeLast(3))
        val ends = run.tickEndStacks()
        assertEquals("c,b", ends[ends.indexOfLast { it == "c" } + 1], "the remembered state follows in the next tick")
    }

    @Test
    fun `an instance starts in a marked state inside a compound state that is not the first of its level`() {
        val run = run(MARKED_LAMP)

        run.awaitStack("on", "dimmed")

        assertEquals(listOf("on", "dimmed"), run.stacks().first())
    }

    @Test
    fun `attachable definitions with container states start inside them for behaves after and EXCHANGE`() {
        val run = run(
            EXCHANGING_ROOM,
            BULB_TEST, QUICK,
            definitions = listOf(PAGED, REPAGED),
            metamodel = ROOM_METAMODEL,
            model = ROOM_MODEL,
            attachedTo = ROOM
        )

        run.await("the lamp in its page") { run.stacksOf(LAMP).lastOrNull() == listOf("page", "page/lit") }
        run.send("swap")
        run.await("the exchanged lamp done") { run.stacksOf(LAMP).lastOrNull() == listOf("again", "again/done") }

        assertEquals(
            listOf(listOf("page", "page/lit"), listOf("again", "again/done")),
            run.stacksOf(LAMP)
        )
    }

    @Test
    @Tag("python")
    fun `ATTACH gives a created object an attachable definition that starts inside its container state`() {
        val run = run(
            ATTACHING_ROOM,
            BULB_TEST, QUICK,
            definitions = listOf(PAGED, REPAGED),
            metamodel = ROOM_METAMODEL,
            model = ROOM_MODEL,
            attachedTo = ROOM
        )

        run.await("the created lamp done") {
            run.engine.stateMachineInstances.any { it.definitionName == "repaged" }
        }
        val created = run.engine.stateMachineInstances.single { it.definitionName == "repaged" }.objectId
        run.await("the created lamp done") { run.stacksOf(created).lastOrNull() == listOf("again", "again/done") }
    }

    /** Goes into `page` of [historyLamp], on to `high` inside `second`, and leaves the container again. */
    private fun enterStepsAndLeave(run: Run) {
        run.awaitStack("off")
        run.send("test")
        run.awaitStack("page", "page/first")
        run.send("next")
        run.awaitStack("page", "page/second", "page/high")
        run.send("abort")
        run.awaitStack("off")
    }

    /** Splits into `left` and `right` and joins them with `DONE` once both rest on final states. */
    private fun joinOverTwoPages(features: Array<KClass<out Feature>>) {
        val run = run(JOINING_LAMP, BULB_TEST, QUICK, features = features)

        run.awaitStack("off")
        run.send("test")
        run.awaitStack("left", "left/lit", "right", "right/done")
        val reached = run.ticks()
        run.await("three more ticks") { run.ticks() >= reached + 3 }
        assertEquals(listOf("left", "left/lit", "right", "right/done"), run.stacks().last(), "only one source is done")
        run.send("next")
        run.awaitStack("left", "left/dark", "right", "right/done")
        run.send("next")
        run.awaitStack("both done")

        assertTrue(listOf("left", "left/passed", "right", "right/done") in run.stacks())
        assertEquals(1, run.traces(TraceEventType.JOIN_FIRED).size)
    }

    /** Returns the model of the read [result] and fails on an error. */
    private fun <M : Any> accept(result: ParseResult<M>): M {
        assertTrue(!result.hasErrors, "errors: ${result.errors}")
        return result.model!!
    }

    /** Flattens [machine] with [fragments] as the runtime does at activation and fails on a problem. */
    private fun flat(machine: StateMachine, fragments: List<StateMachine>): StateMachine =
        when (val result = StateMachineFlattener.flatten(machine, fragments)) {
            is FlatteningResult.Flattened -> result.machine
            is FlatteningResult.NotFlattened -> fail(result.problems.joinToString { it.message })
        }

    /**
     * Starts an engine with [host] attached to [attachedTo], flattened with [fragments], and the [definitions]
     * flattened the same way.
     */
    private fun run(
        host: String,
        vararg fragments: String,
        definitions: List<String> = emptyList(),
        features: Array<KClass<out Feature>> = emptyArray(),
        metamodel: String = LAMP_METAMODEL,
        model: String = LAMP_MODEL,
        attachedTo: String = LAMP
    ): Run {
        val manager = FeatureManager()
        features.forEach { manager.requestFeatureActivation(it) }
        val meta = accept(KmetaReader().read(metamodel))
        val read: (String) -> StateMachine = { accept(KstatesReader().read(it.trimIndent())) }
        val pages = fragments.map(read)
        val engineId = "pages-${COUNTER.incrementAndGet()}"
        val engine = Engine(
            metamodel = meta,
            model = accept(KmodelReader(meta).read(model)),
            statemachineMap = mapOf(attachedTo to flat(read(host), pages)),
            tickDelayMS = 1,
            eventBus = EventBus(),
            engineId = engineId,
            traceLogger = EngineTraceLogger(engineId, consoleOutput = false),
            featureManager = manager,
            stateMachineDefinitions = definitions.map { flat(read(it), pages) }.associateBy { it.name!! }
        ).also { engines += it }
        return Run(engine, attachedTo).also { engine.start() }
    }

    /** A started engine with what it reported. */
    private class Run(val engine: Engine, private val objectId: String) {

        /** Every trace entry so far, in order. */
        private val entries = CopyOnWriteArrayList<EngineTraceLogger.TraceEntry>()

        /** Every reported active stack, with the key of its object, in order. */
        private val reported = CopyOnWriteArrayList<Pair<String, List<String>>>()

        init {
            engine.traceLogger!!.addTraceListener { entries += it }
            engine.onActiveStackChanged = { id, stack -> reported += id to stack }
        }

        /** The reported stacks of the object of the host state machine, in order. */
        fun stacks(): List<List<String>> = stacksOf(objectId)

        /** The reported stacks of [id], in order. */
        fun stacksOf(id: String): List<List<String>> = reported.filter { it.first == id }.map { it.second }

        fun traces(type: TraceEventType) = entries.filter { it.eventType == type && it.modelElementId == objectId }

        /** The number of ticks the host state machine has ended. */
        fun ticks(): Int = traces(TraceEventType.TICK_END).size

        /** The stacks at the end of every tick of the host state machine, as the trace log writes them. */
        fun tickEndStacks(): List<String> = traces(TraceEventType.TICK_END).map { it.details.getValue("stack") }

        /**
         * The ticks in which an ENTRY block with actions ran for the host state machine, in order, counted from the
         * first tick of the engine.
         */
        fun entryTicks(): List<Int> {
            val snapshot = entries.toList().filter { it.modelElementId == objectId }
            var tick = 0
            val ticks = ArrayList<Int>()
            for (entry in snapshot) {
                if (entry.eventType == TraceEventType.TICK_START) tick = entry.message.removePrefix("Tick #").toInt()
                if (entry.eventType == TraceEventType.STATE_ENTRY_EXEC) ticks += tick
            }
            return ticks
        }

        /** Sends the event [name] of the domain `t` from outside. */
        fun send(name: String) = engine.receiveExternalEvent(Event("t", name))

        /** Waits until the last reported stack of the host state machine is [states]. */
        fun awaitStack(vararg states: String) = await("the stack ${states.toList()}") {
            stacks().lastOrNull() == states.toList()
        }

        /** Waits until the list `log` of the object of the host state machine ends with [entry]. */
        fun awaitLog(entry: String) = await("the log entry $entry") { log().lastOrNull() == entry }

        /** The list `log` of the object of the host state machine, read between ticks. */
        fun log(): List<String> = engine.readBetweenTicks(AWAIT_MS) {
            engine.modelQueryProcessor.getDataObjectById(objectId).getProp("log").map { it.toString() }
        } ?: emptyList()

        fun await(what: String, reached: () -> Boolean) {
            val deadline = System.currentTimeMillis() + AWAIT_MS
            while (!reached()) {
                if (System.currentTimeMillis() > deadline) {
                    fail("The engine did not reach $what; stacks ${reported.toList()}")
                }
                Thread.sleep(2)
            }
        }
    }

    private companion object {
        /** The deadline of every wait of these tests. */
        const val AWAIT_MS = 10_000L

        /** Numbers the engines, so every engine has an id of its own. */
        val COUNTER = AtomicInteger()

        const val LAMP = "lamp"
        const val ROOM = "room"

        val LAMP_METAMODEL = """
            type "Lamp" "A lamp" {
                prop("log", list("string"))
            }
        """.trimIndent()

        val LAMP_MODEL = """make object "lamp":"Lamp" { }"""

        val ROOM_METAMODEL = """
            type "Room" "A room with lamps" {
                prop("log", list("string"))
                has("lamp", "Lamp")
                has("spares", list("Lamp"))
            }
            type "Lamp" "A lamp" behaves after "paged" {
                prop("log", list("string"))
            }
        """.trimIndent()

        val ROOM_MODEL = """
            make object "room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" { }
            }
        """.trimIndent()

        /** Lights the bulb, darkens it on `next` and passes on the next `next`. */
        val BULB_TEST = """
            STATEMACHINE "bulb test" FRAGMENT {
                STATES {
                    INITIAL STATE "lit" { ENTRY { APPEND("log", "lit") } }
                    STATE "dark" { ENTRY { APPEND("log", "dark") } }
                    FINAL STATE "passed" { }
                }
                TRANSITIONS {
                    TRANSITION "lit" -> "dark" { CONDITION { EVENT("t", "next") } }
                    TRANSITION "dark" -> "passed" { CONDITION { EVENT("t", "next") } }
                }
            }
        """

        /** Goes from its start to its final state in the tick after it was entered. */
        val QUICK = """
            STATEMACHINE "quick" FRAGMENT {
                STATES {
                    INITIAL STATE "start" { }
                    FINAL STATE "done" { }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "done" { }
                }
            }
        """

        /** Starts in `first` and goes on `next` to `high` inside the compound state `second`. */
        val STEPS = """
            STATEMACHINE "steps" FRAGMENT {
                STATES {
                    INITIAL STATE "first" { }
                    STATE "second" {
                        STATE "low" { }
                        STATE "high" { }
                    }
                    FINAL STATE "end" { }
                }
                TRANSITIONS {
                    TRANSITION "first" -> "high" { CONDITION { EVENT("t", "next") } }
                }
            }
        """

        val SELF_TEST_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    CONTAINER STATE "self test" USE "bulb test" { }
                    STATE "on" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "self test" { CONDITION { EVENT("t", "test") } }
                    TRANSITION "self test" -> "on" { CONDITION { DONE } }
                    TRANSITION "self test" -> "off" { CONDITION { EVENT("t", "abort") } }
                }
            }
        """

        val OUTER_PAGE = """
            STATEMACHINE "outer page" FRAGMENT {
                STATES {
                    INITIAL CONTAINER STATE "middle" USE "middle page" { }
                    FINAL STATE "end" { }
                }
                TRANSITIONS {
                    TRANSITION "middle" -> "end" { CONDITION { DONE } }
                }
            }
        """

        val MIDDLE_PAGE = """
            STATEMACHINE "middle page" FRAGMENT {
                STATES {
                    INITIAL CONTAINER STATE "inner" USE "inner page" { }
                    FINAL STATE "end" { }
                }
                TRANSITIONS {
                    TRANSITION "inner" -> "end" { CONDITION { DONE } }
                }
            }
        """

        /** Starts in its marked second state `b`. */
        val INNER_PAGE = """
            STATEMACHINE "inner page" FRAGMENT {
                STATES {
                    STATE "a" { }
                    INITIAL STATE "b" { }
                    FINAL STATE "end" { }
                }
                TRANSITIONS {
                    TRANSITION "a" -> "b" { }
                    TRANSITION "b" -> "end" { CONDITION { EVENT("t", "next") } }
                }
            }
        """

        val NESTED_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    CONTAINER STATE "top" USE "outer page" { }
                    STATE "on" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "top" { CONDITION { EVENT("t", "test") } }
                    TRANSITION "top" -> "on" { CONDITION { DONE } }
                }
            }
        """

        val TWO_PAGES_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    CONTAINER STATE "one" USE "bulb test" { }
                    CONTAINER STATE "two" USE "bulb test" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "one" { CONDITION { EVENT("t", "test") } }
                    TRANSITION "one" -> "two" { CONDITION { EVENT("t", "swap") } }
                    TRANSITION "two" -> "one" { CONDITION { EVENT("t", "swap") } }
                }
            }
        """

        val COMPOUND_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    STATE "check" {
                        STATE "idle" { }
                        CONTAINER STATE "page" USE "bulb test" { }
                    }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "check" { CONDITION { EVENT("t", "test") } }
                    TRANSITION "off" -> "page" { CONDITION { EVENT("t", "direct") } }
                    TRANSITION "check" -> "page" { CONDITION { EVENT("t", "go") } }
                    TRANSITION "check" -> "off" { CONDITION { EVENT("t", "abort") } }
                }
            }
        """

        /** A lamp whose container state `page` uses `steps` and has the history [kind], `SHALLOW` or `DEEP`. */
        fun historyLamp(kind: String) = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    $kind HISTORY CONTAINER STATE "page" USE "steps" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "page" { CONDITION { EVENT("t", "test") } }
                    TRANSITION "page" -> "off" { CONDITION { EVENT("t", "abort") } }
                }
            }
        """

        val SPLITTING_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL CONTAINER STATE "c" USE "quick" { }
                    STATE "bulb" { }
                    STATE "socket" { }
                }
                TRANSITIONS {
                    SPLIT "c" -> "bulb", "socket" { CONDITION { DONE } }
                }
            }
        """

        val JOINING_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    CONTAINER STATE "left" USE "bulb test" { }
                    CONTAINER STATE "right" USE "quick" { }
                    STATE "both done" { }
                }
                TRANSITIONS {
                    SPLIT "off" -> "left", "right" { CONDITION { EVENT("t", "test") } }
                    JOIN "left", "right" -> "both done" { CONDITION { DONE } }
                }
            }
        """

        /** A fragment whose marked state is its second one. */
        val LEVELS = """
            STATEMACHINE "levels" FRAGMENT {
                STATES {
                    STATE "full" { }
                    INITIAL STATE "dimmed" { }
                    FINAL STATE "out" { }
                }
                TRANSITIONS { }
            }
        """

        /** The container state `on` comes before `spare`, which holds the mark of the state machine itself. */
        val HIDDEN_MARK_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    STATE "off" { }
                    CONTAINER STATE "on" USE "levels" { }
                    STATE "spare" {
                        INITIAL STATE "ready" { }
                    }
                }
                TRANSITIONS { }
            }
        """

        /** The self test written with a compound state: the transitions lead into its inner states. */
        val COMPOUND_SELF_TEST = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    STATE "self test" {
                        STATE "lit" { ENTRY { APPEND("log", "lit") } }
                        STATE "dark" { ENTRY { APPEND("log", "dark") } }
                        STATE "passed" { }
                    }
                    STATE "on" { ENTRY { APPEND("log", "on") } }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "lit" { }
                    TRANSITION "lit" -> "dark" { }
                    TRANSITION "dark" -> "passed" { }
                    TRANSITION "passed" -> "on" { }
                }
            }
        """

        /** The bulb test without events: lit, dark and passed one tick after the other. */
        val RUNNING_TEST = """
            STATEMACHINE "bulb test" FRAGMENT {
                STATES {
                    INITIAL STATE "lit" { ENTRY { APPEND("log", "lit") } }
                    STATE "dark" { ENTRY { APPEND("log", "dark") } }
                    FINAL STATE "passed" { }
                }
                TRANSITIONS {
                    TRANSITION "lit" -> "dark" { }
                    TRANSITION "dark" -> "passed" { }
                }
            }
        """

        /** The self test written with a container state that uses [RUNNING_TEST]. */
        val CONTAINER_SELF_TEST = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    CONTAINER STATE "self test" USE "bulb test" { }
                    STATE "on" { ENTRY { APPEND("log", "on") } }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "self test" { }
                    TRANSITION "self test" -> "on" { CONDITION { DONE } }
                }
            }
        """

        /** A compound state `c` with a shallow history around `a` and `b`. */
        val COMPOUND_HISTORY_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    SHALLOW HISTORY STATE "c" {
                        STATE "a" { }
                        STATE "b" { }
                    }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "a" { CONDITION { EVENT("t", "test") } }
                    TRANSITION "a" -> "b" { CONDITION { EVENT("t", "next") } }
                    TRANSITION "c" -> "off" { CONDITION { EVENT("t", "abort") } }
                    TRANSITION "off" -> "c" { CONDITION { EVENT("t", "back") } }
                }
            }
        """

        val MARKED_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    STATE "off" { }
                    STATE "on" {
                        STATE "full" { }
                        INITIAL STATE "dimmed" { }
                    }
                }
                TRANSITIONS { }
            }
        """

        val PAGED = """
            STATEMACHINE "paged" ATTACHABLE TO "Lamp" {
                STATES {
                    INITIAL CONTAINER STATE "page" USE "bulb test" { }
                }
                TRANSITIONS { }
            }
        """

        val REPAGED = """
            STATEMACHINE "repaged" ATTACHABLE TO "Lamp" {
                STATES {
                    INITIAL CONTAINER STATE "again" USE "quick" { }
                }
                TRANSITIONS { }
            }
        """

        val EXCHANGING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "swapped" { ENTRY { EXCHANGE("lamp", "repaged") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "swapped" { CONDITION { EVENT("t", "swap") } }
                }
            }
        """

        /** Creates a spare lamp with `ATTACH "repaged"` in tick 2. Needs Python. */
        val ATTACHING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "stocked" { ENTRY { APPENDOBJ("self", "spares", EVAL { return {"log": []} }) ATTACH "repaged" } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "stocked" { }
                }
            }
        """
    }
}
