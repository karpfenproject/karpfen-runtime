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
import io.karpfen.dsl.instance.DataObject
import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.reader.ParseResult
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.dsl.states.StateMachine
import io.karpfen.io.karpfen.exec.InstanceSpec
import io.karpfen.io.karpfen.exec.StateMachineObserver
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.language.TickLimitFeature
import io.karpfen.io.karpfen.features.runtime.TrueParallelFeature
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.messages.EventPublicationListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the life cycle of state machine instances on a real engine: instances for `behaves after` at the start, new
 * instances for created objects, `EXCHANGE`, the end of instances whose objects are removed, the order of the steps,
 * separate instances, the errors of `ATTACH` and `EXCHANGE`, and all of it with the feature TrueParallel on.
 *
 * The state machines advance on transitions without conditions, so every run takes the same course tick by tick. The
 * tests read that course from the trace log (which instance started a step in which tick, which instance joined and
 * left after which tick) and wait for conditions with generous deadlines, never for a fixed time. Tests that need
 * Python, because only an `EVAL` or a `MACRO` can build a new object or return a string, carry the tag `python`.
 */
class StateMachineLifecycleEngineTest {

    /** The engines the tests started, stopped after each test. */
    private val engines = ArrayList<Engine>()

    @AfterEach
    fun stopEngines() {
        engines.forEach { it.stop() }
    }

    @Test
    fun `a start gives objects that behave after a definition an instance after the attached state machines`() {
        val run = run(attached = mapOf("room" to IDLE_ROOM, "b2" to STEADY_BULB))

        run.awaitTick(3)

        assertEquals(listOf("room", "b2", "b1", "b3"), run.steps(1))
        assertEquals(
            listOf("room" to null, "b2" to null, "b1" to "glowing", "b3" to "glowing"),
            run.engine.stateMachineInstances.map { it.objectId to it.definitionName }
        )
        assertEquals(listOf("+room", "+b2", "+b1", "+b3"), run.observed.toList())
        assertEquals(listOf(0, 0, 0, 0), run.joinTicks())
        assertEquals(listOf("dark", "lit"), run.log("b1"))
        assertEquals(listOf("dark", "lit"), run.log("b3"))
        assertEquals(listOf("steady"), run.log("b2"))
    }

    @Test
    fun `before the first start the instances are those the start will build`() {
        val engine = engine(mapOf("room" to IDLE_ROOM))

        assertEquals(listOf("room", "b1", "b2", "b3"), engine.stateMachineInstances.map { it.objectId })
        assertEquals("glowing", engine.stateMachineInstances.last().definitionName)
    }

    @Test
    fun `an engine is not built while a type behaves after a missing definition`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            engine(mapOf("room" to IDLE_ROOM), definitions = listOf(BLINKING))
        }

        assertEquals(
            "Types behave after state machines the engine cannot attach: 'Bulb' behaves after 'glowing'",
            refusal.message
        )
    }

    @Test
    fun `removing an object ends the instances of the object and of everything it holds`() {
        val run = run(attached = mapOf("lamp" to IDLE_LAMP, "room" to DROPPING_ROOM))

        run.awaitTick(4)

        assertEquals(listOf("lamp", "room", "b1", "b2", "b3"), run.steps(1))
        assertEquals(listOf("lamp", "room", "b2", "b3"), run.steps(2), "b1 comes after the removal and does no step")
        assertEquals(listOf("room", "b2", "b3"), run.steps(3))
        assertEquals(listOf("+lamp", "+room", "+b1", "+b2", "+b3", "-lamp", "-b1"), run.observed.toList())
        assertEquals(listOf(2, 2), run.leaveTicks())
        assertEquals(listOf("room", "b2", "b3"), run.engine.stateMachineInstances.map { it.objectId })
    }

    @Test
    fun `an instance that removes an object holding its own object finishes its block and leaves`() {
        val run = run(attached = mapOf("b1" to SELF_DROPPING_BULB))

        run.awaitTick(4)

        assertEquals(listOf("before", "after"), run.seen())
        assertEquals(listOf("b2", "b3"), run.steps(3))
        assertEquals(listOf(2), run.leaveTicks())
        assertTrue(run.traces(TraceEventType.STATE_ENTRY_EXEC).none { it.message.contains("'never'") })
    }

    @Test
    fun `EXCHANGE replaces the instance of another object at its place in the order`() {
        val run = run(attached = mapOf("room" to EXCHANGING_ROOM))

        run.awaitTick(5)

        assertEquals(listOf("room", "b2", "b3"), run.steps(2), "the exchanged instance of b1 does no further step")
        assertEquals(listOf("room", "b1", "b2", "b3"), run.steps(3))
        assertEquals(listOf("-b1", "+b1"), run.observed.drop(4))
        assertEquals(listOf("dark", "off", "on"), run.log("b1").take(3))
        assertEquals(listOf("dark", "lit"), run.log("b3"))
        assertEquals("blinking", run.engine.stateMachineInstances[1].definitionName)
    }

    @Test
    fun `EXCHANGE on the own object reached over a path ends the instance after its block`() {
        val run = run(attached = mapOf("b1" to SELF_EXCHANGING_BULB))

        run.awaitTick(4)

        assertEquals(listOf("before", "after"), run.seen())
        assertEquals(listOf("b1", "b2", "b3"), run.steps(3))
        assertEquals(listOf("-b1", "+b1"), run.observed.drop(3))
        assertEquals(listOf(2), run.joinTicks().drop(3))
        assertEquals("glowing", run.engine.stateMachineInstances.first().definitionName)
        assertEquals(listOf("dark"), run.log("b1").take(1))
    }

    @Test
    fun `EXCHANGE on an object without a state machine attaches one at the end of the order`() {
        val run = run(attached = mapOf("room" to GIVING_ROOM))

        run.awaitTick(4)

        assertEquals(listOf("room", "b1", "b2", "b3", "lamp"), run.steps(3))
        assertEquals(listOf("lamp" to "blinking"), run.engine.stateMachineInstances.drop(4).map { it.objectId to it.definitionName })
    }

    @Test
    fun `a failing name fails EXCHANGE and ATTACH, and a failed ATTACH creates nothing`() {
        val run = run(attached = mapOf("room" to FAILING_ROOM), definitions = listOf(GLOWING, BLINKING, LAMPISH))

        run.awaitTick(10)

        val errors = run.traces(TraceEventType.ACTION_ERROR).map { it.message }
        val expected = listOf(
            "No attachable state machine is named 'unknown'",
            "The state machine 'glowing' is attachable to 'Bulb', not to 'Lamp'",
            "The name of a state machine must be a string, but the value is 3.0",
            "EXCHANGE path 'lamp->bulb->holder->bulb->log' does not resolve to an object",
            "No attachable state machine is named 'missing'",
            "The name of a state machine must be a string, but the value is 2.0",
            "ATTACH needs a has relation, but 'favourite' is a knows relation; use EXCHANGE for an existing object",
            "The state machine 'lampish' is attachable to 'Lamp', not to 'Bulb'"
        )
        assertEquals(expected.size, errors.size, "errors: $errors")
        expected.zip(errors).forEach { (text, error) -> assertTrue(error.endsWith(text), "error: $error") }
        assertEquals(listOf("b3"), run.engine.readBetweenTicks(AWAIT_MS) { run.relation("room", "spares") })
        assertEquals(listOf("b2"), run.engine.readBetweenTicks(AWAIT_MS) { run.relation("room", "spare") })
        assertEquals(emptyList(), run.engine.readBetweenTicks(AWAIT_MS) { run.relation("room", "favourite") })
        assertEquals(
            listOf("room" to null, "b1" to "glowing", "b2" to "glowing", "b3" to "glowing"),
            run.engine.stateMachineInstances.map { it.objectId to it.definitionName }
        )
    }

    @Test
    fun `two objects on one definition are separate instances with stacks and tick counts of their own`() {
        val run = run(
            attached = mapOf("room" to SLOWING_ROOM),
            definitions = listOf(GLOWING, SLOW),
            features = arrayOf(TickLimitFeature::class)
        )

        run.awaitTick(9)

        val doneB2 = run.entryTick("b2", "done")
        val doneB1 = run.entryTick("b1", "done")
        assertEquals(doneB2 + 1, doneB1, "the second instance on b1 counts its ticks from its own start")
        assertEquals(listOf("b1", "b2"), run.traces(TraceEventType.STATE_MACHINE_DETACHED).map { it.modelElementId }.take(2))
    }

    @Test
    fun `objects that enter the model get instances for behaves after that step from the next tick on`() {
        val run = run(attached = mapOf("room" to IDLE_ROOM))
        run.awaitTick(2)
        val newLamp = readModelObject(run.metamodel, """
            make object "lamp2":"Lamp" {
                has("bulb") -> make object "nb":"Bulb" { }
            }
        """)
        val newSpare = readModelObject(run.metamodel, """make object "nb2":"Bulb" { }""")

        run.engine.modelQueryProcessor.setObjectRelation("room", "lamp", newLamp)
        run.engine.modelQueryProcessor.appendObjectRelation("room", "spares", newSpare)
        run.await("the new instances") { run.engine.stateMachineInstances.size == 5 }
        run.await("their steps") { run.log("nb2").size == 2 }

        assertEquals(
            listOf("room" to null, "b2" to "glowing", "b3" to "glowing", "nb" to "glowing", "nb2" to "glowing"),
            run.engine.stateMachineInstances.map { it.objectId to it.definitionName }
        )
        assertEquals(listOf("-b1", "+nb", "+nb2"), run.observed.drop(4))
        for (objectId in listOf("nb", "nb2")) {
            assertEquals(run.joinTick(objectId) + 1, run.firstStepTick(objectId), "first step of $objectId")
        }
    }

    @Test
    fun `with TrueParallel branches that remove and exchange at the same time leave a consistent order`() {
        val barrier = CyclicBarrier(3)
        val meetings = AtomicInteger()
        val run = run(
            attached = mapOf("room" to SPLITTING_ROOM),
            features = arrayOf(TrueParallelFeature::class),
            onEvent = { domain, _ ->
                if (domain == "meet") {
                    barrier.await(AWAIT_MS, TimeUnit.MILLISECONDS)
                    meetings.incrementAndGet()
                }
            }
        )

        run.await("the three branches met") { meetings.get() == 3 }
        run.await("the changes of the tick") {
            run.engine.stateMachineInstances.map { it.objectId } == listOf("room", "b2", "b3")
        }
        val tick = run.leaveTicks().first()
        run.awaitTick(tick + 2)

        assertEquals(
            listOf("room" to null, "b2" to "blinking", "b3" to "glowing"),
            run.engine.stateMachineInstances.map { it.objectId to it.definitionName }
        )
        assertEquals(setOf("-b1", "-b2", "+b2"), run.observed.drop(4).toSet())
        assertFalse(run.observed.contains("+lamp"), "the exchange of the removed lamp is dropped or fails")
        assertEquals(listOf("room", "b2", "b3"), run.steps(tick + 1))
        assertFalse(run.engine.readBetweenTicks(AWAIT_MS) { run.engine.modelQueryProcessor.containsObject("lamp") }!!)
    }

    @Test
    @Tag("python")
    fun `ATTACH names the state machine of the created object in place of behaves after`() {
        val run = run(attached = mapOf("room" to CREATING_ROOM))

        run.await("the created bulbs") { run.engine.stateMachineInstances.size == 6 }

        val created = run.engine.stateMachineInstances.drop(4)
        assertEquals(listOf("blinking", "glowing"), created.map { it.definitionName })
        created.forEach { assertEquals(run.joinTick(it.objectId) + 1, run.firstStepTick(it.objectId)) }
    }

    @Test
    @Tag("python")
    fun `a replacing SETOBJ ends the old object and the new object and what it holds behave after their types`() {
        val run = run(attached = mapOf("room" to REPLACING_ROOM))

        run.await("the new bulb") { run.engine.stateMachineInstances.size == 4 && "b1" !in run.objectIds() }

        assertEquals(listOf("-b1"), run.observed.filter { it.startsWith("-") })
        assertEquals("glowing", run.engine.stateMachineInstances.last().definitionName)
    }

    @Test
    @Tag("python")
    fun `the name of EXCHANGE and ATTACH can come from EVAL, MACRO and a WITH binding`() {
        val run = run(attached = mapOf("room" to NAMING_ROOM))

        run.await("three exchanges") { run.observed.count { it == "+b1" } == 3 && run.observed.count { it == "+b2" } == 2 }

        assertEquals("glowing", run.engine.stateMachineInstances.first { it.objectId == "b1" }.definitionName)
        assertEquals("blinking", run.engine.stateMachineInstances.first { it.objectId == "b2" }.definitionName)
    }

    // ---- Running ------------------------------------------------------------------------------------------------

    /** Reads [text] and fails the test when the read found an error. */
    private fun <M : Any> accept(result: ParseResult<M>): M {
        assertTrue(!result.hasErrors, "errors: ${result.errors}")
        return result.model!!
    }

    /** Reads the root object of a KModel [text] against [metamodel]. */
    private fun readModelObject(metamodel: Metamodel, text: String): DataObject =
        accept(KmodelReader(metamodel).read(text.trimIndent())).objects.single()

    /** Builds an engine over a fresh model with the [attached] state machines and the [definitions]. */
    private fun engine(
        attached: Map<String, String>,
        definitions: List<String> = listOf(GLOWING, BLINKING),
        features: Array<KClass<out Feature>> = emptyArray(),
        bus: EventBus = EventBus()
    ): Engine {
        val manager = FeatureManager()
        features.forEach { manager.requestFeatureActivation(it) }
        val metamodel = accept(KmetaReader().read(METAMODEL))
        val model: Model = accept(KmodelReader(metamodel).read(MODEL))
        val read: (String) -> StateMachine = { accept(KstatesReader().read(it)) }
        val known = definitions.map(read).associateBy { it.name!! }
        val engineId = "life-${COUNTER.incrementAndGet()}"
        return Engine(
            metamodel = metamodel,
            model = model,
            statemachineMap = attached.mapValues { read(it.value) },
            tickDelayMS = 1,
            eventBus = bus,
            engineId = engineId,
            traceLogger = EngineTraceLogger(engineId, consoleOutput = false),
            featureManager = manager,
            stateMachineDefinitions = known
        ).also { engines += it }
    }

    /** Starts an engine like [engine] and calls [onEvent] with the domain and name of every published event. */
    private fun run(
        attached: Map<String, String>,
        definitions: List<String> = listOf(GLOWING, BLINKING),
        features: Array<KClass<out Feature>> = emptyArray(),
        onEvent: (String, String) -> Unit = { _, _ -> }
    ): Run {
        val events = CopyOnWriteArrayList<Pair<String, String>>()
        val bus = EventBus()
        bus.addPublicationListener(EventPublicationListener { event ->
            events += event.domain to event.name
            onEvent(event.domain, event.name)
        })
        val engine = engine(attached, definitions, features, bus)
        return Run(engine, events).also { engine.start() }
    }

    /**
     * A running engine and what it tells: the trace log, the published events and the instances reported to its
     * [StateMachineObserver] as `+key` and `-key`.
     */
    private class Run(val engine: Engine, private val events: List<Pair<String, String>>) {

        val metamodel: Metamodel = engine.metamodel

        /** Every trace entry so far, in order. */
        private val entries = CopyOnWriteArrayList<EngineTraceLogger.TraceEntry>()

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

        fun traces(type: TraceEventType) = entries.filter { it.eventType == type }

        /** The objects whose instances started a step in [tick], in order. */
        fun steps(tick: Int): List<String> =
            traces(TraceEventType.TICK_START).filter { it.message == "Tick #$tick" }.map { it.modelElementId }

        /** The number of the tick in which the entry at [index] was logged, or 0 before the first tick. */
        private fun tickAt(index: Int): Int {
            for (i in index downTo 0) {
                val entry = entries[i]
                if (entry.eventType == TraceEventType.TICK_START) return entry.message.removePrefix("Tick #").toInt()
            }
            return 0
        }

        /** The ticks after which instances joined, 0 for the start, in order. */
        fun joinTicks(): List<Int> = ticksOf(TraceEventType.STATE_MACHINE_ATTACHED)

        /** The ticks in which instances left, in order. */
        fun leaveTicks(): List<Int> = ticksOf(TraceEventType.STATE_MACHINE_DETACHED)

        private fun ticksOf(type: TraceEventType): List<Int> {
            val snapshot = entries.toList()
            return snapshot.indices.filter { snapshot[it].eventType == type }.map(::tickAt)
        }

        /** The tick after which the last instance of [objectId] joined. */
        fun joinTick(objectId: String): Int {
            val snapshot = entries.toList()
            val index = snapshot.indexOfLast {
                it.eventType == TraceEventType.STATE_MACHINE_ATTACHED && it.modelElementId == objectId
            }
            return tickAt(index)
        }

        /** The first tick in which [objectId] started a step after its last instance joined. */
        fun firstStepTick(objectId: String): Int {
            val joined = joinTick(objectId)
            return traces(TraceEventType.TICK_START)
                .filter { it.modelElementId == objectId }
                .map { it.message.removePrefix("Tick #").toInt() }
                .first { it > joined }
        }

        /** The tick in which the ENTRY block of [state] ran for [objectId]. */
        fun entryTick(objectId: String, state: String): Int {
            val snapshot = entries.toList()
            val index = snapshot.indexOfFirst {
                it.eventType == TraceEventType.STATE_ENTRY_EXEC && it.modelElementId == objectId &&
                    it.message.contains("'$state'")
            }
            assertTrue(index >= 0, "no ENTRY of '$state' for $objectId")
            return tickAt(index)
        }

        /** The names of the events of the domain `seen`, in order. */
        fun seen(): List<String> = events.filter { it.first == "seen" }.map { it.second }

        /** The keys of the objects of the instances. */
        fun objectIds(): List<String> = engine.stateMachineInstances.map(InstanceSpec::objectId)

        /** The list `log` of [objectId], read between ticks. */
        fun log(objectId: String): List<String> = engine.readBetweenTicks(AWAIT_MS) {
            if (!engine.modelQueryProcessor.containsObject(objectId)) emptyList()
            else engine.modelQueryProcessor.getDataObjectById(objectId).getProp("log").map { it.toString() }
        } ?: emptyList()

        /** The keys of the objects in the relation [key] of [objectId]; call between ticks. */
        fun relation(objectId: String, key: String): List<String> =
            engine.modelQueryProcessor.getDataObjectById(objectId).getRel(key).map { it.id }

        /** Waits until the engine started tick [tick] + 1, so tick [tick] is over. */
        fun awaitTick(tick: Int) = await("tick ${tick + 1}") { traces(TraceEventType.TICK_START).any { it.message == "Tick #${tick + 1}" } }

        fun await(what: String, reached: () -> Boolean) {
            val deadline = System.currentTimeMillis() + AWAIT_MS
            while (!reached()) {
                if (System.currentTimeMillis() > deadline) fail("The engine did not reach $what")
                Thread.sleep(2)
            }
        }
    }

    private companion object {
        /** The deadline of every wait of these tests. */
        const val AWAIT_MS = 10_000L

        /** Gives every engine an id of its own. */
        val COUNTER = AtomicInteger()

        val METAMODEL = """
            type "Room" "A room with a lamp and spare bulbs" {
                prop("log", list("string"))
                has("lamp", "Lamp")
                has("spare", "Bulb")
                has("spares", list("Bulb"))
                knows("favourite", "Bulb")
            }
            type "Lamp" "A lamp with a bulb" {
                prop("log", list("string"))
                has("bulb", "Bulb")
            }
            type "Bulb" "A light bulb" behaves after "glowing" {
                prop("log", list("string"))
                knows("holder", "Lamp")
            }
        """.trimIndent()

        val MODEL = """
            make object "room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" {
                    has("bulb") -> make object "b1":"Bulb" {
                        knows("holder") -> "lamp"
                    }
                }
                has("spare") -> make object "b2":"Bulb" { }
                has("spares") -> make object "b3":"Bulb" { }
            }
        """.trimIndent()

        /** Every bulb by default: enters `dark`, then `lit` in the next tick, and stays. */
        val GLOWING = """
            STATEMACHINE "glowing" ATTACHABLE TO "Bulb" {
                STATES {
                    INITIAL STATE "dark" { ENTRY { APPEND("log", "dark") } }
                    STATE "lit" { ENTRY { APPEND("log", "lit") } }
                }
                TRANSITIONS {
                    TRANSITION "dark" -> "lit" { }
                }
            }
        """.trimIndent()

        /** Switches between `off` and `on` in every tick; fits bulbs and lamps. */
        val BLINKING = """
            STATEMACHINE "blinking" ATTACHABLE TO "Bulb", "Lamp" {
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

        /** Fits lamps only. */
        val LAMPISH = """
            STATEMACHINE "lampish" ATTACHABLE TO "Lamp" {
                STATES { INITIAL STATE "idle" { } }
                TRANSITIONS { }
            }
        """.trimIndent()

        /** Leads to `done` after three ticks in `wait`. */
        val SLOW = """
            STATEMACHINE "slow" ATTACHABLE TO "Bulb" {
                STATES {
                    INITIAL STATE "wait" {
                        TICK LIMIT { TIMEOUT: AFTER 3 TICKS TRANSITION TO "done" }
                    }
                    STATE "done" { ENTRY { APPEND("log", "done") } }
                }
                TRANSITIONS { }
            }
        """.trimIndent()

        val IDLE_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES { INITIAL STATE "idle" { } }
                TRANSITIONS { }
            }
        """.trimIndent()

        val IDLE_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES { INITIAL STATE "idle" { } }
                TRANSITIONS { }
            }
        """.trimIndent()

        val STEADY_BULB = """
            STATEMACHINE ATTACHED TO "Bulb" {
                STATES { INITIAL STATE "steady" { ENTRY { APPEND("log", "steady") } } }
                TRANSITIONS { }
            }
        """.trimIndent()

        /** Removes the lamp, and with it the bulb `b1`, in tick 2. */
        val DROPPING_ROOM = room("""ENTRY { DROPOBJ("lamp") APPEND("log", "dropped") }""")

        /** Gives `b1` the state machine `blinking` in tick 2. */
        val EXCHANGING_ROOM = room("""ENTRY { EXCHANGE("lamp->bulb", "blinking") }""")

        /** Gives the lamp, which has no state machine, `blinking` in tick 2. */
        val GIVING_ROOM = room("""ENTRY { EXCHANGE("lamp", "blinking") }""")

        /** Gives both bulbs a `slow` in tick 2 and `b1` a second one in tick 3. */
        val SLOWING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "first" { ENTRY { EXCHANGE("lamp->bulb", "slow") EXCHANGE("spare", "slow") } }
                    STATE "again" { ENTRY { EXCHANGE("lamp->bulb", "slow") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "first" { }
                    TRANSITION "first" -> "again" { }
                }
            }
        """.trimIndent()

        /** One failing rule per state, from tick 2 on; each is followed by the next state. */
        val FAILING_ROOM = chain(
            """EXCHANGE("lamp->bulb", "unknown")""",
            """EXCHANGE("lamp", "glowing")""",
            """EXCHANGE("lamp->bulb", EVAL { return 3 })""",
            """EXCHANGE("lamp->bulb->holder->bulb->log", "glowing")""",
            """APPENDOBJ("self", "spares", EVAL { return 1 }) ATTACH "missing"""",
            """SETOBJ("self", "spare", EVAL { return 1 }) ATTACH EVAL { return 2 }""",
            """SETOBJ("self", "favourite", "spare") ATTACH "blinking"""",
            """APPENDOBJ("self", "spares", EVAL { return 1 }) ATTACH "lampish""""
        )

        /** Removes the lamp, which holds its own object, in tick 2, and raises events before and after. */
        val SELF_DROPPING_BULB = bulb("""ENTRY { EVENT("seen", "before") DROPOBJ("holder") EVENT("seen", "after") }""")

        /** Exchanges its own state machine for `glowing` in tick 2, and raises events before and after. */
        val SELF_EXCHANGING_BULB = bulb(
            """ENTRY { EVENT("seen", "before") EXCHANGE("holder->bulb", "glowing") EVENT("seen", "after") }"""
        )

        /** Three branches that meet and then remove the lamp, exchange the spare and exchange the lamp at once. */
        val SPLITTING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "init" { }
                    STATE "left" { ENTRY { EVENT("meet", "left") DROPOBJ("lamp") } }
                    STATE "right" { ENTRY { EVENT("meet", "right") EXCHANGE("spare", "blinking") } }
                    STATE "mid" { ENTRY { EVENT("meet", "mid") EXCHANGE("lamp", "blinking") } }
                }
                TRANSITIONS {
                    SPLIT "init" -> "left", "right", "mid" { }
                }
            }
        """.trimIndent()

        /** Creates a spare with ATTACH and one without it, in tick 2. Needs Python. */
        val CREATING_ROOM = room(
            """ENTRY {
                APPENDOBJ("self", "spares", EVAL { return {"log": []} }) ATTACH "blinking"
                APPENDOBJ("self", "spares", EVAL { return {"log": []} })
            }"""
        )

        /** Replaces the lamp with a new one holding a new bulb, in tick 2. Needs Python. */
        val REPLACING_ROOM = room("""ENTRY { SETOBJ("self", "lamp", EVAL { return {"log": [], "bulb": {"log": []}} }) }""")

        /** Exchanges with names from EVAL, MACRO and WITH. Needs Python. */
        val NAMING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "eval" { ENTRY { EXCHANGE("lamp->bulb", EVAL { return "blinking" }) } }
                    STATE "macro" { ENTRY { EXCHANGE("spare", MACRO("pick")) } }
                    STATE "with" {
                        ENTRY {
                            WITH MACRO("glow") AS "choice" {
                                EXCHANGE("lamp->bulb", EVAL { return $(choice) })
                            }
                        }
                    }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "eval" { }
                    TRANSITION "eval" -> "macro" { }
                    TRANSITION "macro" -> "with" { }
                }
                MACROS {
                    MACRO "pick" {
                        RETURNS("string")
                        DEFINITION { EVAL { return "blinking" } }
                    }
                    MACRO "glow" {
                        RETURNS("string")
                        DEFINITION { EVAL { return "glowing" } }
                    }
                }
            }
        """.trimIndent()

        /** A room state machine that does [entry] in the ENTRY block of its second state, in tick 2. */
        fun room(entry: String) = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "act" { $entry }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "act" { }
                }
            }
        """.trimIndent()

        /** A bulb state machine that does [entry] in its second state, in tick 2, and never reaches `never`. */
        fun bulb(entry: String) = """
            STATEMACHINE ATTACHED TO "Bulb" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "act" { $entry }
                    STATE "never" { ENTRY { EVENT("seen", "never") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "act" { }
                    TRANSITION "act" -> "never" { }
                }
            }
        """.trimIndent()

        /** A room state machine whose states from tick 2 on each run one of [rules] in their ENTRY block. */
        fun chain(vararg rules: String): String {
            val states = rules.indices.joinToString("\n") { "STATE \"s$it\" { ENTRY { ${rules[it]} } }" }
            val transitions = (listOf("start") + rules.indices.map { "s$it" }).zipWithNext()
                .joinToString("\n") { (from, to) -> "TRANSITION \"$from\" -> \"$to\" { }" }
            return """
                STATEMACHINE ATTACHED TO "Room" {
                    STATES {
                        INITIAL STATE "start" { }
                        $states
                    }
                    TRANSITIONS {
                        $transitions
                    }
                }
            """.trimIndent()
        }
    }
}
