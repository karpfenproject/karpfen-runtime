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
import io.karpfen.EngineTraceLogger.TraceEntry
import io.karpfen.EngineTraceLogger.TraceEventType
import io.karpfen.dsl.dsl.textual.KmetaDSLConverter
import io.karpfen.dsl.dsl.textual.KmodelDSLConverter
import io.karpfen.dsl.dsl.textual.KstatesDSLConverter
import io.karpfen.io.karpfen.exec.StateMachineObserver
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.runtime.TrueParallelFeature
import io.karpfen.io.karpfen.messages.EventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The paths in the trace log and the single entry of an exchange: every entry of a state machine names the path or the
 * paths of its instance, the numbers run per object for the life of the engine and are never handed out twice, a split
 * names the paths it starts, a join, a forced state and an exchange the paths they end and the path they start, and an
 * exchange writes one `STATE_MACHINE_EXCHANGED` entry in place of a detached and an attached entry.
 *
 * The state machines advance on transitions without conditions, so every run takes the same course, and the tests wait
 * for entries of the trace with generous deadlines, never for a fixed time.
 */
class TracePathTest {

    /** The engines the tests started, closed after each test. */
    private val engines = ArrayList<Engine>()

    @AfterEach
    fun closeEngines() {
        engines.forEach { it.close() }
    }

    @Test
    fun `without a split every entry of a state machine is on path 0`() {
        val run = run(mapOf("lamp" to LAMP_CYCLE))
        run.awaitTickEnd(5)
        val entries = run.finish()

        val own = entries.filter { it.modelElementId != "*" }
        assertTrue(own.isNotEmpty())
        own.forEach { assertEquals("0", it.details["path"], "path of $it") }
        entries.filter { it.modelElementId == "*" }.forEach {
            assertTrue("path" !in it.details && "paths" !in it.details, "an entry of the engine names no path: $it")
        }
        val attached = entries.indexOfFirst { it.eventType == TraceEventType.STATE_MACHINE_ATTACHED && it.modelElementId == "lamp" }
        val initial = entries[attached + 1]
        assertEquals(TraceEventType.INITIAL_STATE, initial.eventType)
        assertEquals(mapOf("path" to "0", "stack" to "x"), initial.details)
        assertEquals(mapOf("stateMachine" to "", "path" to "0"), entries[attached].details)
    }

    @Test
    fun `a split starts one path per target, a join ends them in a path of its own, and a second split counts on`() {
        val run = run(mapOf("lamp" to LAMP_TEST))
        run.await("two joins") { run.traces(TraceEventType.JOIN_FIRED).size >= 2 }
        val entries = run.finish()

        val lamp = entries.filter { it.modelElementId == "lamp" }
        assertEquals(
            listOf(mapOf("path" to "0"), mapOf("path" to "3")),
            lamp.ofType(TraceEventType.SPLIT_START).take(2).map { it.details.only("path") }
        )
        assertEquals(
            listOf(mapOf("path" to "0", "starts" to "1,2"), mapOf("path" to "3", "starts" to "4,5")),
            lamp.ofType(TraceEventType.SPLIT_FIRED).take(2).map { it.details.only("path", "starts") }
        )
        assertEquals(
            listOf(mapOf("paths" to "1,2"), mapOf("paths" to "4,5")),
            lamp.ofType(TraceEventType.JOIN_START).take(2).map { it.details.only("paths") }
        )
        assertEquals(
            listOf(mapOf("ends" to "1,2", "path" to "3"), mapOf("ends" to "4,5", "path" to "6")),
            lamp.ofType(TraceEventType.JOIN_FIRED).take(2).map { it.details.only("ends", "path") }
        )
        val states = statesByPath(lamp)
        assertEquals(setOf("idle", "off"), states["0"])
        assertEquals(setOf("test bulb", "bulb ok"), states["1"])
        assertEquals(setOf("test switch", "check position"), states["2"], "the join fires before switch ok is entered")
        assertEquals(
            listOf("1" to "bulb ok", "2" to "switch ok"),
            lamp.ofType(TraceEventType.TRANSITION_FIRED).filter { it.details["to"] in setOf("bulb ok", "switch ok") }
                .take(2).map { it.details.getValue("path") to it.details.getValue("to") }
        )
        assertEquals(setOf("on", "off"), states["3"])
        assertEquals(states["1"], states["4"])
        assertEquals(states["2"], states["5"])
        val ticks = lamp.filter { it.eventType == TraceEventType.TICK_START || it.eventType == TraceEventType.TICK_END }
        ticks.filter { "paths" in it.details }.forEach {
            assertTrue(it.details["paths"] in setOf("1,2", "4,5", "7,8"), "paths of a split tick: $it")
            assertTrue("path" !in it.details)
        }
        lamp.forEach { assertTrue("path" in it.details || "paths" in it.details, "no path in $it") }
    }

    @Test
    fun `TrueParallel gives every entry the path of round robin`() {
        val courses = listOf(false, true).map { trueParallel ->
            val features = if (trueParallel) arrayOf<KClass<out Feature>>(TrueParallelFeature::class) else emptyArray()
            val run = run(mapOf("lamp" to LAMP_TEST), *features)
            run.await("two joins") { run.traces(TraceEventType.JOIN_FIRED).size >= 2 }
            val lamp = run.finish().filter { it.modelElementId == "lamp" }
            val byPath = (0..5).associate { path ->
                path.toString() to lamp.filter { it.details["path"] == path.toString() && "state" in it.details }
                    .map { Triple(it.eventType, it.message, it.details) }
            }
            val changes = lamp.filter { it.eventType == TraceEventType.SPLIT_FIRED || it.eventType == TraceEventType.JOIN_FIRED }
                .take(4).map { it.eventType to it.details }
            byPath to changes
        }

        assertEquals(courses[0].second, courses[1].second)
        for (path in 0..5) {
            assertEquals(courses[0].first[path.toString()], courses[1].first[path.toString()], "the entries of path $path")
        }
    }

    @Test
    fun `a stop and a start in the middle of a split keep the paths`() {
        val run = run(mapOf("lamp" to LAMP_SPLIT_LOOP))
        run.await("the split") { run.traces(TraceEventType.SPLIT_FIRED).isNotEmpty() }
        val splitTick = run.lastTick()
        run.awaitTickEnd(splitTick + 2)

        run.engine.stop()
        val stoppedAt = run.engine.tickCount
        val mark = run.size()
        run.engine.start()
        run.awaitTickEnd(stoppedAt.toInt() + 3)
        val entries = run.finish()

        val after = entries.drop(mark).filter { it.modelElementId == "lamp" }
        assertTrue(after.isNotEmpty())
        after.forEach { entry ->
            val paths = entry.details["path"] ?: entry.details["paths"]
            assertTrue(paths in setOf("1", "2", "1,2"), "paths after the start: $entry")
        }
        val states = statesByPath(after)
        assertTrue(states.getValue("1").all { it.startsWith("a") }, "path 1: ${states["1"]}")
        assertTrue(states.getValue("2").all { it.startsWith("b") }, "path 2: ${states["2"]}")
        assertEquals(1, entries.count { it.eventType == TraceEventType.SPLIT_FIRED })
    }

    @Test
    fun `a forced state ends the paths of a split and starts the next path`() {
        val run = run(mapOf("lamp" to LAMP_SPLIT_LOOP))
        run.await("the split") { run.traces(TraceEventType.SPLIT_FIRED).isNotEmpty() }
        run.awaitTickEnd(run.lastTick() + 1)

        run.engine.forceActiveState("lamp", "parked")
        run.await("the forced state") { run.forced() != null }
        val forcedAt = run.indexOf(run.forced()!!)
        run.awaitTickEnd(run.lastTick() + 2)
        val entries = run.finish()

        val forced = entries[forcedAt]
        assertEquals("Forced state: parked", forced.message)
        assertEquals(mapOf("ends" to "1,2", "path" to "3", "stack" to "parked"), forced.details)
        val after = entries.drop(forcedAt + 1).filter { it.modelElementId == "lamp" }
        assertTrue(after.any { it.eventType == TraceEventType.TICK_END })
        after.forEach { assertEquals("3", it.details["path"], "path after the forced state: $it") }
    }

    @Test
    fun `an exchange outside a split is one entry naming both state machines and their paths`() {
        val run = run(mapOf("lamp" to LAMP_CYCLE, "room" to EXCHANGING_ROOM))
        run.await("two exchanges") { run.traces(TraceEventType.STATE_MACHINE_EXCHANGED).size == 2 }
        run.awaitTickEnd(run.lastTick() + 2)
        val entries = run.finish()

        val exchanges = entries.ofType(TraceEventType.STATE_MACHINE_EXCHANGED)
        assertEquals(listOf("lamp", "lamp"), exchanges.map { it.modelElementId })
        assertEquals("State machine of the run configuration exchanged for 'blinking'", exchanges[0].message)
        assertEquals(mapOf("ended" to "", "started" to "blinking", "ends" to "0", "path" to "1"), exchanges[0].details)
        assertEquals("State machine 'blinking' exchanged for 'cycling'", exchanges[1].message)
        assertEquals(mapOf("ended" to "blinking", "started" to "cycling", "ends" to "1", "path" to "2"), exchanges[1].details)
        for ((exchange, path) in exchanges.zip(listOf("1", "2"))) {
            val next = entries[entries.indexOf(exchange) + 1]
            assertEquals(TraceEventType.INITIAL_STATE, next.eventType)
            assertEquals(path, next.details["path"])
        }
        assertEquals(1, entries.count { it.eventType == TraceEventType.STATE_MACHINE_ATTACHED && it.modelElementId == "lamp" })
        assertTrue(entries.none { it.eventType == TraceEventType.STATE_MACHINE_DETACHED })
        assertEquals(listOf("+lamp", "-lamp", "+lamp", "-lamp", "+lamp"), run.observed.filter { it.endsWith("lamp") })
        val last = entries.last { it.modelElementId == "lamp" && it.eventType == TraceEventType.TICK_END }
        assertEquals("2", last.details["path"])
    }

    @Test
    fun `two exchanges of one object in one tick read as one exchange`() {
        val run = run(mapOf("lamp" to LAMP_CYCLE, "room" to DOUBLE_EXCHANGING_ROOM))
        run.await("the exchange") { run.traces(TraceEventType.STATE_MACHINE_EXCHANGED).isNotEmpty() }
        run.awaitTickEnd(run.lastTick() + 2)
        val entries = run.finish()

        val exchange = entries.ofType(TraceEventType.STATE_MACHINE_EXCHANGED).single()
        assertEquals("State machine of the run configuration exchanged for 'cycling'", exchange.message)
        assertEquals(mapOf("ended" to "", "started" to "cycling", "ends" to "0", "path" to "1"), exchange.details)
        assertEquals(listOf("+lamp", "-lamp", "+lamp"), run.observed.filter { it.endsWith("lamp") })
    }

    @Test
    fun `EXCHANGE on an object without a state machine stays an attachment`() {
        val run = run(mapOf("room" to GIVING_ROOM))
        run.await("the attachment") {
            run.traces(TraceEventType.STATE_MACHINE_ATTACHED).any { it.modelElementId == "lamp" }
        }
        val entries = run.finish()

        val attached = entries.single { it.eventType == TraceEventType.STATE_MACHINE_ATTACHED && it.modelElementId == "lamp" }
        assertEquals("State machine 'blinking' attached", attached.message)
        assertEquals(mapOf("stateMachine" to "blinking", "path" to "0"), attached.details)
        assertTrue(entries.none { it.eventType == TraceEventType.STATE_MACHINE_EXCHANGED })
    }

    @Test
    fun `a branch of a split that exchanges another object keeps its path and the object counts on its own`() {
        val run = run(mapOf("lamp" to LAMP_CYCLE, "room" to SPLIT_EXCHANGING_ROOM))
        run.await("the exchange") { run.traces(TraceEventType.STATE_MACHINE_EXCHANGED).isNotEmpty() }
        run.awaitTickEnd(run.lastTick() + 1)
        val entries = run.finish()

        val exchange = entries.ofType(TraceEventType.STATE_MACHINE_EXCHANGED).single()
        assertEquals("lamp", exchange.modelElementId)
        assertEquals(mapOf("ended" to "", "started" to "blinking", "ends" to "0", "path" to "1"), exchange.details)
        val room = statesByPath(entries.filter { it.modelElementId == "room" })
        assertEquals(setOf("init"), room["0"])
        assertEquals(setOf("x"), room["1"])
        assertEquals(setOf("y"), room["2"])
    }

    @Test
    fun `an exchange of a split instance ends all its paths, in round robin and with TrueParallel`() {
        for (features in listOf(emptyArray(), arrayOf<KClass<out Feature>>(TrueParallelFeature::class))) {
            val run = run(mapOf("lamp" to LAMP_SELF_EXCHANGING), *features)
            run.await("the exchange") { run.traces(TraceEventType.STATE_MACHINE_EXCHANGED).isNotEmpty() }
            run.awaitTickEnd(run.lastTick() + 2)
            val entries = run.finish()

            val exchange = entries.ofType(TraceEventType.STATE_MACHINE_EXCHANGED).single()
            assertEquals(mapOf("ended" to "", "started" to "blinking", "ends" to "1,2", "path" to "3"), exchange.details)
            val after = entries.drop(entries.indexOf(exchange) + 1).filter { it.modelElementId == "lamp" }
            assertTrue(after.any { it.eventType == TraceEventType.TICK_END })
            after.forEach { assertEquals("3", it.details["path"], "path after the exchange: $it") }
        }
    }

    @Test
    fun `an attachment after a detach on the same key goes on with the next path`() {
        val run = run(mapOf("lamp" to LAMP_CYCLE, "room" to DROPPING_ROOM))
        run.await("the detach") { run.traces(TraceEventType.STATE_MACHINE_DETACHED).any { it.modelElementId == "b1" } }
        val detached = run.traces(TraceEventType.STATE_MACHINE_DETACHED)
        assertEquals(
            listOf(
                "lamp" to mapOf("stateMachine" to "", "path" to "0"),
                "b1" to mapOf("stateMachine" to "cycling", "path" to "0")
            ),
            detached.map { it.modelElementId to it.details }
        )
        assertEquals("State machine of the run configuration detached", detached[0].message)

        val lamp = KmodelDSLConverter.parseKmodelString(
            """make object "lamp":"Lamp" { has("bulb") -> make object "b1":"Bulb" { } }""",
            run.engine.metamodel
        ).objects.single()
        run.engine.modelQueryProcessor.setObjectRelation("room", "lamp", lamp)
        run.await("the new instance") {
            run.traces(TraceEventType.STATE_MACHINE_ATTACHED).count { it.modelElementId == "b1" } == 2
        }
        run.awaitTickEnd(run.lastTick() + 2)
        val entries = run.finish()

        val attached = entries.filter { it.eventType == TraceEventType.STATE_MACHINE_ATTACHED && it.modelElementId == "b1" }.last()
        assertEquals(mapOf("stateMachine" to "cycling", "path" to "1"), attached.details)
        val after = entries.drop(entries.indexOf(attached)).filter { it.modelElementId == "b1" }
        assertTrue(after.any { it.eventType == TraceEventType.TICK_END })
        after.forEach { assertEquals("1", it.details["path"], "path of the new instance: $it") }
    }

    /** The states named in the entries of [entries], by the path of each entry. */
    private fun statesByPath(entries: List<TraceEntry>): Map<String, Set<String>> =
        entries.filter { "state" in it.details && "path" in it.details }
            .groupBy({ it.details.getValue("path") }, { it.details.getValue("state") })
            .mapValues { it.value.toSet() }

    private fun List<TraceEntry>.ofType(type: TraceEventType) = filter { it.eventType == type }

    private fun Map<String, String>.only(vararg keys: String) = filterKeys { it in keys }

    /** Starts an engine over the room, its lamp and bulb, with the [attached] state machines and [features] on. */
    private fun run(attached: Map<String, String>, vararg features: KClass<out Feature>): Run {
        val manager = FeatureManager()
        features.forEach { manager.requestFeatureActivation(it) }
        val metamodel = KmetaDSLConverter.parseKmetaString(METAMODEL)
        val engineId = "paths-${COUNTER.incrementAndGet()}"
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
        return Run(engine).also { engine.start() }
    }

    /**
     * A running engine, its trace and the instances reported to its [StateMachineObserver] as `+key` and `-key`.
     *
     * @property engine the engine.
     */
    private class Run(val engine: Engine) {

        /** Every trace entry of the engine so far, in the order it was written. */
        private val entries = CopyOnWriteArrayList<TraceEntry>()

        /** The instances that joined (`+key`) and left (`-key`), in order. */
        val observed = CopyOnWriteArrayList<String>()

        init {
            engine.traceLogger!!.addTraceListener { entries += it }
            engine.stateMachineObserver = object : StateMachineObserver {
                override fun onStateMachineAttached(objectId: String, definitionName: String?) {
                    observed += "+$objectId"
                }

                override fun onStateMachineDetached(objectId: String, definitionName: String?) {
                    observed += "-$objectId"
                }
            }
        }

        /** The trace entries of [type] so far, in order. */
        fun traces(type: TraceEventType): List<TraceEntry> = entries.filter { it.eventType == type }

        /** The number of entries so far. */
        fun size(): Int = entries.size

        /** The position of [entry] in the trace. */
        fun indexOf(entry: TraceEntry): Int = entries.indexOf(entry)

        /** The entry of the forced state, or null before it. */
        fun forced(): TraceEntry? =
            entries.firstOrNull { it.eventType == TraceEventType.INITIAL_STATE && it.message.startsWith("Forced") }

        /** The number of the latest tick that started. */
        fun lastTick(): Int = traces(TraceEventType.TICK_START).maxOf { numberOf(it) }

        /** Ends the engine for good and returns the whole trace. */
        fun finish(): List<TraceEntry> {
            engine.close()
            return entries.toList()
        }

        /** Waits until the tick [tick] has ended. */
        fun awaitTickEnd(tick: Int) =
            await("the end of tick $tick") { traces(TraceEventType.TICK_END).any { numberOf(it) >= tick } }

        /** Waits until [reached] holds, and fails when it does not within [AWAIT_MS]. */
        fun await(what: String, reached: () -> Boolean) {
            val deadline = System.currentTimeMillis() + AWAIT_MS
            while (!reached()) {
                if (System.currentTimeMillis() > deadline) fail("The engine did not reach $what")
                Thread.sleep(2)
            }
        }

        private fun numberOf(entry: TraceEntry): Int = assertNotNull(TICK_NUMBER.find(entry.message)).groupValues[1].toInt()
    }

    private companion object {
        /** How long a test waits for a condition before it fails. */
        const val AWAIT_MS = 10_000L

        /** Gives every engine an id of its own. */
        val COUNTER = AtomicInteger()

        /** Finds the number in "Tick #3". */
        val TICK_NUMBER = Regex("#(\\d+)")

        val METAMODEL = """
            type "Room" "A room with a lamp" {
                has("lamp", "Lamp")
            }
            type "Lamp" "A lamp with a bulb" {
                has("bulb", "Bulb")
                knows("me", "Lamp")
            }
            type "Bulb" "A light bulb" behaves after "cycling" {
            }
        """.trimIndent()

        val MODEL = """
            make object "room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" {
                    has("bulb") -> make object "b1":"Bulb" { }
                    knows("me") -> "lamp"
                }
            }
        """.trimIndent()

        /** Enters `a`, `b` and `c` one after the other and starts over. */
        val CYCLING = """
            STATEMACHINE "cycling" ATTACHABLE TO "Bulb", "Lamp" {
                STATES {
                    INITIAL STATE "a" { }
                    STATE "b" { }
                    STATE "c" { }
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
            STATEMACHINE "blinking" ATTACHABLE TO "Bulb", "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    STATE "on" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "on" { }
                    TRANSITION "on" -> "off" { }
                }
            }
        """.trimIndent()

        /** Switches between `x` and `y`, without a split. */
        val LAMP_CYCLE = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "x" { }
                    STATE "y" { }
                }
                TRANSITIONS {
                    TRANSITION "x" -> "y" { }
                    TRANSITION "y" -> "x" { }
                }
            }
        """.trimIndent()

        /** Tests bulb and switch in two branches, joins them in `on`, and splits again from `off`. */
        val LAMP_TEST = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "idle" { }
                    STATE "off" { }
                    STATE "test bulb" { }
                    STATE "bulb ok" { }
                    STATE "test switch" { }
                    STATE "check position" { }
                    STATE "switch ok" { }
                    STATE "on" { }
                }
                TRANSITIONS {
                    TRANSITION "idle" -> "off" { }
                    SPLIT "off" -> "test bulb", "test switch" { }
                    TRANSITION "test bulb" -> "bulb ok" { }
                    TRANSITION "test switch" -> "check position" { }
                    TRANSITION "check position" -> "switch ok" { }
                    JOIN "bulb ok", "switch ok" -> "on" { }
                    TRANSITION "on" -> "off" { }
                }
            }
        """.trimIndent()

        /** Splits into two branches that cycle for ever; `parked` is reached only by force. */
        val LAMP_SPLIT_LOOP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    STATE "a1" { }
                    STATE "a2" { }
                    STATE "b1" { }
                    STATE "b2" { }
                    STATE "parked" { }
                }
                TRANSITIONS {
                    SPLIT "off" -> "a1", "b1" { }
                    TRANSITION "a1" -> "a2" { }
                    TRANSITION "a2" -> "a1" { }
                    TRANSITION "b1" -> "b2" { }
                    TRANSITION "b2" -> "b1" { }
                }
            }
        """.trimIndent()

        /** Splits, and its second branch exchanges the state machine of its own object for `blinking`. */
        val LAMP_SELF_EXCHANGING = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    STATE "a1" { }
                    STATE "a2" { }
                    STATE "b1" { ENTRY { EXCHANGE("me", "blinking") } }
                }
                TRANSITIONS {
                    SPLIT "off" -> "a1", "b1" { }
                    TRANSITION "a1" -> "a2" { }
                    TRANSITION "a2" -> "a1" { }
                }
            }
        """.trimIndent()

        /** Exchanges the state machine of the lamp for `blinking`, and in the next tick for `cycling`. */
        val EXCHANGING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "swap" { ENTRY { EXCHANGE("lamp", "blinking") } }
                    STATE "again" { ENTRY { EXCHANGE("lamp", "cycling") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "swap" { }
                    TRANSITION "swap" -> "again" { }
                }
            }
        """.trimIndent()

        /** Exchanges the state machine of the lamp twice in one block. */
        val DOUBLE_EXCHANGING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "swap" { ENTRY { EXCHANGE("lamp", "blinking") EXCHANGE("lamp", "cycling") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "swap" { }
                }
            }
        """.trimIndent()

        /** Gives the lamp, which has no state machine, `blinking`. */
        val GIVING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "give" { ENTRY { EXCHANGE("lamp", "blinking") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "give" { }
                }
            }
        """.trimIndent()

        /** Splits into `x` and `y`; `y` exchanges the state machine of the lamp. */
        val SPLIT_EXCHANGING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "init" { }
                    STATE "x" { }
                    STATE "y" { ENTRY { EXCHANGE("lamp", "blinking") } }
                }
                TRANSITIONS {
                    SPLIT "init" -> "x", "y" { }
                }
            }
        """.trimIndent()

        /** Removes the lamp, and with it the bulb. */
        val DROPPING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "drop" { ENTRY { DROPOBJ("lamp") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "drop" { }
                }
            }
        """.trimIndent()
    }
}
