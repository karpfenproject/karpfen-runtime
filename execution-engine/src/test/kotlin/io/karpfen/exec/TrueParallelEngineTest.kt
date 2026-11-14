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
import io.karpfen.io.karpfen.exec.ModelChangePublisher
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.runtime.BreakpointFeature
import io.karpfen.io.karpfen.features.runtime.TickByTickFeature
import io.karpfen.io.karpfen.features.runtime.TrueParallelFeature
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.messages.EventPublicationListener
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs split state machines on a real engine with and without the feature TrueParallel. The state machines use literal
 * actions only, no Python. The tests wait for conditions with generous deadlines and never pass or fail by sleeping.
 *
 * Parallelism is shown with a barrier for two parties in the listener of the event bus: each branch raises an event in
 * its `DO` block and waits there for the other branch, so a meeting needs both branches inside their steps at once.
 */
class TrueParallelEngineTest {

    /** The engines the tests started, stopped after each test. */
    private val engines = ArrayList<Engine>()

    @AfterEach
    fun stopEngines() {
        engines.forEach { it.stop() }
    }

    @Test
    fun `with the feature on two branches are inside their steps at the same time`() {
        val barrier = CyclicBarrier(2)
        val meetings = AtomicInteger()
        val missed = AtomicInteger()
        val threads = CopyOnWriteArrayList<String>()
        val run = run("meet-on", MEETING_MACHINE, TrueParallelFeature::class.java) { _, _ ->
            threads += Thread.currentThread().name
            try {
                barrier.await(AWAIT_MS, TimeUnit.MILLISECONDS)
                meetings.incrementAndGet()
            } catch (_: Exception) {
                missed.incrementAndGet()
            }
        }

        await("six meetings") { meetings.get() >= 6 }

        assertEquals(0, missed.get())
        val names = threads.toSet()
        assertEquals(2, names.size, "threads: $names")
        assertTrue(names.all { it.startsWith("Branch-${run.engineId}-") }, "threads: $names")
    }

    @Test
    fun `with the feature off the same branches step one after the other on the engine thread`() {
        val steps = CopyOnWriteArrayList<Pair<String, String>>()
        val run = run("meet-off", MEETING_MACHINE) { name, thread -> steps += name to thread }

        await("six steps") { steps.size >= 6 }

        assertEquals(listOf("a", "b", "a", "b", "a", "b"), steps.take(6).map { it.first })
        assertTrue(steps.all { it.second == "Engine-${run.engineId}" }, "steps: $steps")
        assertTrue(branchThreads(run.engineId).isEmpty())
    }

    @Test
    fun `a join after parallel branches merges them and ends their threads`() {
        val run = run("join", JOIN_MACHINE, TrueParallelFeature::class.java)

        await("the join") { run.traces(TraceEventType.JOIN_FIRED).isNotEmpty() }
        await("the end of the branch threads") { branchThreads(run.engineId).isEmpty() }
        await("the entry of the merged state") { run.log().contains("M") }

        val log = run.log()
        assertTrue(log.containsAll(listOf("L1", "R1")), "log: $log")
        assertEquals(1, log.count { it == "M" }, "log: $log")
    }

    @Test
    fun `switching the feature in the middle of a run takes effect at a tick boundary`() {
        val steps = CopyOnWriteArrayList<String>()
        val run = run("switch", MEETING_MACHINE) { _, thread -> steps += thread }

        await("steps on the engine thread") { steps.size >= 4 }
        run.features.requestFeatureActivation(TrueParallelFeature::class)
        await("steps on branch threads") { steps.count { it.startsWith("Branch-") } >= 4 }
        assertEquals(2, branchThreads(run.engineId).size)

        run.features.requestFeatureDeactivation(TrueParallelFeature::class)
        await("the end of the branch threads") { branchThreads(run.engineId).isEmpty() }
        val afterOff = steps.size
        await("steps on the engine thread again") { steps.size >= afterOff + 4 }

        assertTrue(steps.drop(afterOff).all { it == "Engine-${run.engineId}" }, "steps: $steps")
    }

    @Test
    fun `stopping the engine ends the threads of its branches`() {
        val run = run("stop", MEETING_MACHINE, TrueParallelFeature::class.java)
        await("two branch threads") { branchThreads(run.engineId).size == 2 }

        run.engine.stop()

        await("the end of the branch threads") { branchThreads(run.engineId).isEmpty() }
        assertTrue(!run.engine.isTicking)
    }

    @Test
    fun `many branches writing different properties lose no write and no publication`() {
        val publications = AtomicInteger()
        val inPublication = AtomicInteger()
        val overlapped = AtomicBoolean(false)
        val publisher = ModelChangePublisher { _, _ ->
            if (inPublication.incrementAndGet() > 1) overlapped.set(true)
            publications.incrementAndGet()
            inPublication.decrementAndGet()
        }
        val run = run("stress", STRESS_MACHINE, TrueParallelFeature::class.java, publisher = publisher)

        await("fifty appends in every list") {
            run.engine.readBetweenTicks(AWAIT_MS) { (1..BRANCHES).all { run.list("l$it").size >= 50 } } == true
        }
        run.engine.stop()

        val sizes = (1..BRANCHES).map { run.list("l$it").size }
        assertEquals(1, sizes.toSet().size, "sizes: $sizes")
        assertTrue((1..BRANCHES).all { k -> run.list("l$k").all { it == "x$k" } })
        assertTrue(!overlapped.get(), "two publications overlapped")
        assertEquals(sizes.sum(), publications.get(), "every DO block publishes its one change")
    }

    @Test
    fun `branches that reach breakpoints in the same tick halt one after the other`() {
        val run = run(
            "halt",
            HALT_MACHINE,
            TrueParallelFeature::class.java,
            BreakpointFeature::class.java
        )

        val first = run.awaitHalt { true }
        assertEquals("read", run.engine.readBetweenTicks(AWAIT_MS) { "read" })
        run.features.onMessage(TickByTickFeature::class, "resume")
        val second = run.awaitHalt { it != first }
        run.features.onMessage(TickByTickFeature::class, "resume")
        val ticks = run.traces(TraceEventType.TICK_END).size
        await("ticks after the halts") { run.traces(TraceEventType.TICK_END).size > ticks + 3 }

        assertEquals(setOf("a", "b"), setOf(first, second))
    }

    /** The threads of branches of the engine [engineId] that are alive now. */
    private fun branchThreads(engineId: String): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("Branch-$engineId-") }

    /**
     * Starts an engine named [engineId] that runs [machine] on the object `t` with [features] switched on and
     * [publisher], when given, registered before the start. [onMeet] is called with the name of every event of the
     * domain `meet` and the name of the thread that raised it.
     */
    private fun run(
        engineId: String,
        machine: String,
        vararg features: Class<out io.karpfen.io.karpfen.features.Feature>,
        publisher: ModelChangePublisher? = null,
        onMeet: (String, String) -> Unit = { _, _ -> }
    ): Run {
        val manager = FeatureManager()
        features.forEach { manager.requestFeatureActivation(it.kotlin) }
        val metamodel = KmetaDSLConverter.parseKmetaString(METAMODEL)
        val bus = EventBus()
        bus.addPublicationListener(EventPublicationListener { event ->
            if (event.domain == "meet") onMeet(event.name, Thread.currentThread().name)
        })
        val engine = Engine(
            metamodel = metamodel,
            model = KmodelDSLConverter.parseKmodelString(MODEL, metamodel),
            statemachineMap = mapOf("t" to KstatesDSLConverter.parseKstatesString(machine)),
            tickDelayMS = 1,
            eventBus = bus,
            engineId = engineId,
            traceLogger = EngineTraceLogger(engineId, consoleOutput = false),
            featureManager = manager
        )
        publisher?.let(engine.modelQueryProcessor::addChangePublisher)
        engines += engine
        return Run(engine, manager, engineId).also { engine.start() }
    }

    /**
     * A running engine and what it tells.
     *
     * @property engine the engine.
     * @property features the features of the engine.
     * @property engineId the id of the engine, also the prefix of its thread names.
     */
    private class Run(val engine: Engine, val features: FeatureManager, val engineId: String) {

        /** Every trace entry of the engine so far. */
        private val entries = CopyOnWriteArrayList<EngineTraceLogger.TraceEntry>()

        init {
            engine.traceLogger!!.addTraceListener { entries += it }
        }

        /** The trace entries of [type] so far, in order. */
        fun traces(type: TraceEventType) = entries.filter { it.eventType == type }

        /** The values of the list property [key] of the object `t`. */
        fun list(key: String): List<String> = engine.model.objects.first().getProp(key).map { it.toString() }

        /** The list property `log` of the object `t`, read between two ticks. */
        fun log(): List<String> = engine.readBetweenTicks(AWAIT_MS) { list("log") } ?: emptyList()

        /** Waits until the engine waits at the entry of a state that [accept] accepts, and returns the state. */
        fun awaitHalt(accept: (String) -> Boolean): String {
            var state: String? = null
            await("a halt") {
                val status = JSONObject(features.onMessage(BreakpointFeature::class, "{\"action\": \"getStatus\"}"))
                state = status.optJSONObject("halt")?.optString("state")?.takeIf { status.getBoolean("waiting") && accept(it) }
                state != null
            }
            return state!!
        }
    }

    private companion object {
        /** The deadline of every wait of these tests. */
        const val AWAIT_MS = 10_000L

        /** The number of branches of the stress machine. */
        const val BRANCHES = 8

        /** Waits until [reached] holds, and fails when it does not within [AWAIT_MS]. */
        fun await(what: String, reached: () -> Boolean) {
            val deadline = System.currentTimeMillis() + AWAIT_MS
            while (!reached()) {
                if (System.currentTimeMillis() > deadline) fail("The engine did not reach $what")
                Thread.sleep(2)
            }
        }

        val METAMODEL = """
            type "Thing" "a thing with lists" {
                prop("log", list("string"))
                ${(1..BRANCHES).joinToString("\n") { "prop(\"l$it\", list(\"string\"))" }}
            }
        """.trimIndent()

        val MODEL = """
            make object "t":"Thing" { }
        """.trimIndent()

        /** Two branches that raise an event of the domain `meet` in every `DO`. */
        val MEETING_MACHINE = """
            STATEMACHINE ATTACHED TO "Thing" {
                STATES {
                    INITIAL STATE "init" { }
                    STATE "a" { DO { EVENT("meet", "a") } }
                    STATE "b" { DO { EVENT("meet", "b") } }
                }
                TRANSITIONS {
                    SPLIT "init" -> "a", "b" { }
                }
            }
        """.trimIndent()

        /** A split into two branches of two states each, merged by a join. */
        val JOIN_MACHINE = """
            STATEMACHINE ATTACHED TO "Thing" {
                STATES {
                    INITIAL STATE "init" { }
                    STATE "left1" { ENTRY { APPEND("log", "L1") } }
                    STATE "left2" { }
                    STATE "right1" { ENTRY { APPEND("log", "R1") } }
                    STATE "right2" { }
                    STATE "merged" { ENTRY { APPEND("log", "M") } }
                }
                TRANSITIONS {
                    SPLIT "init" -> "left1", "right1" { }
                    TRANSITION "left1" -> "left2" { }
                    TRANSITION "right1" -> "right2" { }
                    JOIN "left2", "right2" -> "merged" { }
                }
            }
        """.trimIndent()

        /** Many branches, each appending to a list of its own in every `DO`. */
        val STRESS_MACHINE = """
            STATEMACHINE ATTACHED TO "Thing" {
                STATES {
                    INITIAL STATE "init" { }
                    ${(1..BRANCHES).joinToString("\n") { "STATE \"s$it\" { DO { APPEND(\"l$it\", \"x$it\") } }" }}
                }
                TRANSITIONS {
                    SPLIT "init" -> ${(1..BRANCHES).joinToString(", ") { "\"s$it\"" }} { }
                }
            }
        """.trimIndent()

        /** Two branches whose states halt at their entry. */
        val HALT_MACHINE = """
            STATEMACHINE ATTACHED TO "Thing" {
                STATES {
                    INITIAL STATE "init" { }
                    STATE "a" { BREAKPOINT { ENTRY } }
                    STATE "b" { BREAKPOINT { ENTRY } }
                }
                TRANSITIONS {
                    SPLIT "init" -> "a", "b" { }
                }
            }
        """.trimIndent()
    }
}
