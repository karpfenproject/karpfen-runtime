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
import io.karpfen.dsl.dsl.textual.KmetaDSLConverter
import io.karpfen.dsl.dsl.textual.KmodelDSLConverter
import io.karpfen.dsl.dsl.textual.KstatesDSLConverter
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.language.HistoryFeature
import io.karpfen.io.karpfen.features.language.TickLimitFeature
import io.karpfen.io.karpfen.features.runtime.BreakpointFeature
import io.karpfen.io.karpfen.features.runtime.TickByTickFeature
import io.karpfen.io.karpfen.features.runtime.TrueParallelFeature
import io.karpfen.io.karpfen.features.runtime.event.EventInjectionFeature
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A stop freezes the engine and a start continues it: the instances, also those from `EXCHANGE`, their stacks and
 * branches, the tick number, histories, tick limit counts, the pause of TickByTick, a halt at a breakpoint, the list of
 * EventInjection and the events on the bus all stay, and nothing that ran runs again.
 *
 * The state machines advance on transitions without conditions, so every run takes the same course tick by tick
 * wherever the stop falls. The tests therefore check the whole course after the start against the course of a run
 * without a stop, read from the logs of the objects and from the trace, and wait for conditions with generous
 * deadlines, never for a fixed time.
 */
class EngineFreezeTest {

    /** The engines the tests started, closed after each test. */
    private val engines = ArrayList<Engine>()

    @AfterEach
    fun closeEngines() {
        engines.forEach { it.close() }
    }

    @Test
    fun `a start after a stop continues with the next tick, the same stacks and no ENTRY twice`() {
        val run = run(mapOf("room" to IDLE_ROOM))
        run.awaitTickEnd(3)

        run.engine.stop()
        val stoppedAt = run.engine.tickCount
        val logAtStop = run.log("b1")
        assertFalse(run.engine.isTicking)
        run.engine.start()
        run.awaitTickEnd(stoppedAt + 3)

        assertEquals(stoppedAt.toInt(), logAtStop.size)
        for (bulb in listOf("b1", "b2", "b3")) {
            val log = run.log(bulb)
            assertEquals(cycle(listOf("a", "b", "c"), log.size), log, "the course of $bulb")
        }
        val ticks = run.traces(TraceEventType.TICK_START).filter { it.modelElementId == "b1" }.map { run.numberOf(it) }
        assertEquals((1..ticks.size).toList(), ticks)
        val starts = run.traces(TraceEventType.ENGINE_START).map { it.message }
        assertEquals(2, starts.size)
        assertTrue(starts[1].startsWith("Engine continued after tick $stoppedAt"), starts[1])
        assertEquals(4, run.traces(TraceEventType.STATE_MACHINE_ATTACHED).size)
    }

    @Test
    fun `instances that EXCHANGE gave objects stay across a stop`() {
        val run = run(mapOf("room" to EXCHANGING_ROOM))
        run.awaitTickEnd(4)

        run.engine.stop()
        val instances = run.engine.stateMachineInstances.map { it.objectId to it.definitionName }
        val stoppedAt = run.engine.tickCount
        run.engine.start()
        run.awaitTickEnd(stoppedAt + 2)

        assertEquals(instances, run.engine.stateMachineInstances.map { it.objectId to it.definitionName })
        assertEquals("blinking", instances.single { it.first == "b1" }.second)
        assertEquals(5, run.traces(TraceEventType.STATE_MACHINE_ATTACHED).size)
        val log = run.log("b1")
        assertEquals(listOf("a") + cycle(listOf("off", "on"), log.size - 1), log)
    }

    @Test
    fun `a stop in the middle of a split keeps the branches`() {
        assertSplitContinues(trueParallel = false)
    }

    @Test
    fun `with TrueParallel a stop ends the threads of the branches and the start brings them back`() {
        assertSplitContinues(trueParallel = true)
    }

    @Test
    fun `a stop while TickByTick pauses keeps the pause and the ticks given while frozen wait for the start`() {
        val run = run(mapOf("room" to IDLE_ROOM), TickByTickFeature::class)
        run.awaitTickEnd(2)
        val pause = run.manager.getActiveFeature<TickByTickFeature>()
        run.manager.onMessage(TickByTickFeature::class, "pause")
        run.await("the pause") { pause.isWaiting() }
        val pausedAt = run.engine.tickCount

        run.engine.stop()
        assertFalse(run.engine.isTicking)
        assertEquals("read", run.engine.readBetweenTicks(AWAIT_MS) { "read" })
        run.manager.onMessage(TickByTickFeature::class, "tick:1")
        assertEquals(pausedAt, run.engine.tickCount, "a frozen engine does not tick")
        run.engine.start()

        run.await("the tick given while frozen") { run.engine.tickCount == pausedAt + 1 && pause.isWaiting() }
        assertEquals("paused", run.manager.onMessage(TickByTickFeature::class, "status"))
        run.manager.onMessage(TickByTickFeature::class, "resume")
        run.awaitTickEnd(pausedAt + 3)
        val log = run.log("b1")
        assertEquals(cycle(listOf("a", "b", "c"), log.size), log)
    }

    @Test
    fun `a stop while a breakpoint halts keeps the halt until a resume after the start`() {
        val run = run(mapOf("room" to HALTING_ROOM), BreakpointFeature::class)
        run.awaitHalt()

        run.engine.stop()
        assertFalse(run.engine.isTicking)
        assertEquals(listOf("first"), run.log("room"))
        run.engine.start()

        assertEquals("second", run.status().getJSONObject("halt").getString("state"))
        assertTrue(run.status().getBoolean("waiting"))
        run.manager.onMessage(TickByTickFeature::class, "resume")
        run.await("the state after the breakpoint") { run.log("room").size == 3 }
        assertEquals(listOf("first", "second", "third"), run.log("room"))
        assertEquals(1, run.traces(TraceEventType.STATE_ENTRY_EXEC).count { it.details["state"] == "second" })
    }

    @Test
    fun `events wait across a stop and fire after the start`() {
        val run = run(mapOf("room" to WAITING_ROOM))
        run.awaitTickEnd(2)

        run.engine.stop()
        run.engine.receiveExternalEvent(Event("public", "go"))
        run.engine.start()

        run.await("the transition on the waiting event") { run.log("room") == listOf("step") }
    }

    @Test
    fun `tick limit counts stay across a stop`() {
        val run = run(mapOf("room" to TIMED_ROOM), TickLimitFeature::class)
        run.awaitTickEnd(2)

        run.engine.stop()
        run.engine.start()

        run.await("the timeout") { run.log("room") == listOf("wait", "done") }
        assertEquals(7, run.entryTick("room", "done"))
    }

    @Test
    fun `histories stay across a stop`() {
        val run = run(mapOf("room" to REMEMBERING_ROOM), HistoryFeature::class)
        run.awaitTickEnd(4)

        run.engine.stop()
        val stoppedAt = run.engine.tickCount
        run.engine.start()
        run.awaitTickEnd(stoppedAt + 4)

        val log = run.log("room")
        assertEquals(listOf("first") + cycle(listOf("second", "away"), log.size - 1), log)
    }

    @Test
    fun `EventInjection keeps its list and the tick numbers across a stop`() {
        val run = run(mapOf("room" to WAITING_ROOM), EventInjectionFeature::class) { manager ->
            val go = """{"environmentKey": "public", "messageType": "go", "payload": ""}"""
            manager.onMessage(EventInjectionFeature::class, "upload:{\"6\": [$go]}")
            manager.onMessage(EventInjectionFeature::class, "start")
        }
        run.awaitTickEnd(2)

        run.engine.stop()
        run.engine.start()

        run.await("the injected event") { run.log("room") == listOf("step") }
        assertEquals(7, run.entryTick("room", "step"))
    }

    @Test
    fun `stopping a frozen engine and starting a running one change nothing`() {
        val run = run(mapOf("room" to IDLE_ROOM))
        run.awaitTickEnd(2)

        run.engine.start()
        run.engine.stop()
        run.engine.stop()
        run.engine.start()
        run.engine.start()
        run.awaitTickEnd(run.engine.tickCount + 2)

        assertEquals(2, run.traces(TraceEventType.ENGINE_START).size)
        val log = run.log("b1")
        assertEquals(cycle(listOf("a", "b", "c"), log.size), log)
    }

    /** Runs the split room, stops it in the middle of the split, starts it again and checks the course. */
    private fun assertSplitContinues(trueParallel: Boolean) {
        val features = if (trueParallel) arrayOf<KClass<out Feature>>(TrueParallelFeature::class) else emptyArray()
        val run = run(mapOf("room" to SPLITTING_ROOM), *features)
        run.awaitTickEnd(4)

        run.engine.stop()
        val stoppedAt = run.engine.tickCount
        val branchThreadsAtStop = run.branchThreads()
        run.engine.start()
        run.awaitTickEnd(stoppedAt + 3)

        if (trueParallel) {
            assertEquals(0, branchThreadsAtStop)
            run.await("the threads of the branches") { run.branchThreads() == 2 }
        }
        val log = run.log("room")
        assertEquals(1, log.count { it == "init" })
        val left = log.filter { it.startsWith("l") }
        val right = log.filter { it.startsWith("r") }
        assertEquals(cycle(listOf("l1", "l2"), left.size), left)
        assertEquals(cycle(listOf("r1", "r2"), right.size), right)
        assertTrue(left.size >= stoppedAt.toInt(), "the left branch stepped in every tick after the split")
    }

    /** The first [length] entries of the endless repetition of [period]. */
    private fun cycle(period: List<String>, length: Int): List<String> = List(length) { period[it % period.size] }

    /** Starts an engine with the room and its bulbs, the [attached] state machines and [features] switched on. */
    private fun run(
        attached: Map<String, String>,
        vararg features: KClass<out Feature>,
        prepare: (FeatureManager) -> Unit = {}
    ): Run {
        val manager = FeatureManager()
        features.forEach { manager.requestFeatureActivation(it) }
        prepare(manager)
        val metamodel = KmetaDSLConverter.parseKmetaString(METAMODEL)
        val engineId = "freeze-${COUNTER.incrementAndGet()}"
        val engine = Engine(
            metamodel = metamodel,
            model = KmodelDSLConverter.parseKmodelString(MODEL, metamodel),
            statemachineMap = attached.mapValues { KstatesDSLConverter.parseKstatesString(it.value) },
            tickDelayMS = 1,
            eventBus = EventBus(),
            engineId = engineId,
            traceLogger = EngineTraceLogger(engineId, consoleOutput = false),
            featureManager = manager,
            stateMachineDefinitions = listOf(CYCLING, BLINKING)
                .map(KstatesDSLConverter::parseKstatesString).associateBy { it.name!! }
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

        init {
            engine.traceLogger!!.addTraceListener { entries += it }
        }

        /** The trace entries of [type] so far, in order. */
        fun traces(type: TraceEventType): List<EngineTraceLogger.TraceEntry> = entries.filter { it.eventType == type }

        /** The list `log` of [objectId], read between two ticks. */
        fun log(objectId: String): List<String> = engine.readBetweenTicks(AWAIT_MS) {
            engine.modelQueryProcessor.getDataObjectById(objectId).getProp("log").map { it.toString() }
        } ?: fail("No tick boundary within $AWAIT_MS ms")

        /** The number of live threads of branches of this engine. */
        fun branchThreads(): Int =
            Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith("Branch-${engine.engineId}-") }

        /** What the breakpoint feature answers when it is asked for its status. */
        fun status(): JSONObject = JSONObject(manager.onMessage(BreakpointFeature::class, "{\"action\": \"getStatus\"}"))

        /** Waits until the engine waits at a breakpoint. */
        fun awaitHalt() = await("a halt") { status().let { it.getBoolean("waiting") && it.has("halt") } }

        /** Waits until the tick [tick] has ended. */
        fun awaitTickEnd(tick: Long) =
            await("the end of tick $tick") { traces(TraceEventType.TICK_END).any { numberOf(it) >= tick } }

        /** Waits until the tick [tick] has ended. */
        fun awaitTickEnd(tick: Int) = awaitTickEnd(tick.toLong())

        /** The tick in which [objectId] ran the ENTRY block of [state] the first time. */
        fun entryTick(objectId: String, state: String): Int {
            val all = entries.toList()
            val index = all.indexOfFirst {
                it.eventType == TraceEventType.STATE_ENTRY_EXEC && it.modelElementId == objectId && it.details["state"] == state
            }
            return numberOf(all.subList(0, index).last { it.eventType == TraceEventType.TICK_START })
        }

        /** The tick number in the message of a tick's start or end entry. */
        fun numberOf(entry: EngineTraceLogger.TraceEntry): Int = TICK_NUMBER.find(entry.message)!!.groupValues[1].toInt()

        /** Waits until [reached] holds, and fails when it does not within [AWAIT_MS]. */
        fun await(what: String, reached: () -> Boolean) {
            val deadline = System.currentTimeMillis() + AWAIT_MS
            while (!reached()) {
                if (System.currentTimeMillis() > deadline) fail("The engine did not reach $what")
                Thread.sleep(2)
            }
        }
    }

    private companion object {
        /** How long a test waits for a condition before it fails. */
        const val AWAIT_MS = 10_000L

        /** Gives every engine an id of its own. */
        val COUNTER = AtomicInteger()

        /** Finds the number in "Tick #3". */
        val TICK_NUMBER = Regex("#(\\d+)")

        val METAMODEL = """
            type "Room" "A room with a lamp and spare bulbs" {
                prop("log", list("string"))
                has("lamp", "Lamp")
                has("spares", list("Bulb"))
            }
            type "Lamp" "A lamp with a bulb" {
                prop("log", list("string"))
                has("bulb", "Bulb")
            }
            type "Bulb" "A light bulb" behaves after "cycling" {
                prop("log", list("string"))
            }
        """.trimIndent()

        val MODEL = """
            make object "room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" {
                    has("bulb") -> make object "b1":"Bulb" { }
                }
                has("spares") -> make object "b2":"Bulb" { }
                has("spares") -> make object "b3":"Bulb" { }
            }
        """.trimIndent()

        /** Enters `a`, `b` and `c` one after the other, one in every tick, and starts over. */
        val CYCLING = """
            STATEMACHINE "cycling" ATTACHABLE TO "Bulb" {
                STATES {
                    INITIAL STATE "a" { ENTRY { APPEND("log", "a") } }
                    STATE "b" { ENTRY { APPEND("log", "b") } }
                    STATE "c" { ENTRY { APPEND("log", "c") } }
                }
                TRANSITIONS {
                    TRANSITION "a" -> "b" { }
                    TRANSITION "b" -> "c" { }
                    TRANSITION "c" -> "a" { }
                }
            }
        """.trimIndent()

        /** Switches between `off` and `on` in every tick. */
        val BLINKING = """
            STATEMACHINE "blinking" ATTACHABLE TO "Bulb" {
                STATES {
                    INITIAL STATE "off" { ENTRY { APPEND("log", "off") } }
                    STATE "on" { ENTRY { APPEND("log", "on") } }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "on" { }
                    TRANSITION "on" -> "off" { }
                }
            }
        """.trimIndent()

        val IDLE_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES { INITIAL STATE "idle" { } }
                TRANSITIONS { }
            }
        """.trimIndent()

        /** Gives `b1` the state machine `blinking` in tick 2. */
        val EXCHANGING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "act" { ENTRY { EXCHANGE("lamp->bulb", "blinking") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "act" { }
                }
            }
        """.trimIndent()

        /** Splits into two branches that each enter one of their two states in every tick. */
        val SPLITTING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "init" { ENTRY { APPEND("log", "init") } }
                    STATE "left" {
                        STATE "l1" { ENTRY { APPEND("log", "l1") } }
                        STATE "l2" { ENTRY { APPEND("log", "l2") } }
                    }
                    STATE "right" {
                        STATE "r1" { ENTRY { APPEND("log", "r1") } }
                        STATE "r2" { ENTRY { APPEND("log", "r2") } }
                    }
                }
                TRANSITIONS {
                    SPLIT "init" -> "l1", "r1" { }
                    TRANSITION "l1" -> "l2" { }
                    TRANSITION "l2" -> "l1" { }
                    TRANSITION "r1" -> "r2" { }
                    TRANSITION "r2" -> "r1" { }
                }
            }
        """.trimIndent()

        /** Halts at the entry of `second`, in tick 2. */
        val HALTING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "first" { ENTRY { APPEND("log", "first") } }
                    STATE "second" {
                        BREAKPOINT { ENTRY }
                        ENTRY { APPEND("log", "second") }
                    }
                    STATE "third" { ENTRY { APPEND("log", "third") } }
                }
                TRANSITIONS {
                    TRANSITION "first" -> "second" { }
                    TRANSITION "second" -> "third" { }
                }
            }
        """.trimIndent()

        /** Leads to `step` on the event `go` of the domain `public`. */
        val WAITING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "idle" { }
                    STATE "step" { ENTRY { APPEND("log", "step") } }
                }
                TRANSITIONS {
                    TRANSITION "idle" -> "step" {
                        CONDITION { EVENT("public", "go") }
                    }
                }
            }
        """.trimIndent()

        /** Enters `wait` in tick 1, counts five ticks there and enters `done` in tick 7. */
        val TIMED_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "wait" {
                        TICK LIMIT { TIMEOUT: AFTER 5 TICKS TRANSITION TO "done" }
                        ENTRY { APPEND("log", "wait") }
                    }
                    STATE "done" { ENTRY { APPEND("log", "done") } }
                }
                TRANSITIONS { }
            }
        """.trimIndent()

        /** Leaves `outer` from `second` and comes back to `second` through its history, again and again. */
        val REMEMBERING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL SHALLOW HISTORY STATE "outer" {
                        STATE "first" { ENTRY { APPEND("log", "first") } }
                        STATE "second" { ENTRY { APPEND("log", "second") } }
                    }
                    STATE "away" { ENTRY { APPEND("log", "away") } }
                }
                TRANSITIONS {
                    TRANSITION "first" -> "second" { }
                    TRANSITION "second" -> "away" { }
                    TRANSITION "away" -> "outer" { }
                }
            }
        """.trimIndent()
    }
}
