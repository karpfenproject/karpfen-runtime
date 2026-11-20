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
import io.karpfen.io.karpfen.features.runtime.TrueParallelFeature
import io.karpfen.io.karpfen.features.runtime.event.EventRecordingFeature
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What a forced state does to the events on the bus: every event that is on the bus when the engine applies the force
 * counts as handled by the forced state machine, on every channel, also for the branches of a later split, while every
 * other state machine sees these events as before and an event published after the force fires as any event does. The
 * name of a channel plays no part, also when it equals the key of the object of the forced state machine.
 *
 * The state machine `robot` waits in `asleep`, which no event leaves, and is forced into `ready`, which `go` leaves. The
 * state machine `watcher` wakes on an event of the channel `wake` and then waits for `go`. The tests use no Python and
 * wait for reported stacks and ended ticks with generous deadlines, never for a fixed time.
 */
class ForcedStateEventsTest {

    /** The engines the tests started, closed after each test. */
    private val engines = ArrayList<Engine>()

    @AfterEach
    fun closeEngines() {
        engines.forEach { it.close() }
    }

    @Test
    fun `a force leaves the events of a channel named like the object for another state machine`() {
        val run = run(channel = ROBOT)
        run.awaitStack(ROBOT, "asleep")
        run.awaitStack(WATCHER, "asleep")

        run.send(ROBOT, "go")
        run.force("ready")
        run.send("wake", "now")

        run.awaitStack(WATCHER, "seen")
        run.awaitTicks(3)
        assertEquals(listOf(listOf("asleep"), listOf("ready")), run.stacksOf(ROBOT))
    }

    @Test
    fun `an event waiting before the force does not fire a transition of the forced state machine`() {
        val run = run(channel = "orders")
        run.awaitStack(ROBOT, "asleep")

        run.send("orders", "go")
        run.force("ready")
        run.awaitTicks(3)

        assertEquals(listOf(listOf("asleep"), listOf("ready")), run.stacksOf(ROBOT))
    }

    @Test
    fun `an event published after the force fires a transition of the forced state machine`() {
        val run = run(channel = "orders")
        run.awaitStack(ROBOT, "asleep")

        run.send("orders", "go")
        run.force("ready")
        run.awaitTicks(1)
        run.send("orders", "go")

        run.awaitStack(ROBOT, "moving")
        assertEquals(listOf(listOf("asleep"), listOf("ready"), listOf("moving")), run.stacksOf(ROBOT))
    }

    @Test
    fun `a force does not change what another state machine sees`() {
        val run = run(channel = "orders")
        run.awaitStack(WATCHER, "asleep")

        run.send("orders", "go")
        run.force("ready")
        run.send("wake", "now")

        run.awaitStack(WATCHER, "seen")
        assertEquals(listOf(listOf("asleep"), listOf("waiting"), listOf("seen")), run.stacksOf(WATCHER))
        assertTrue(run.forcedEntries().all { it.modelElementId == ROBOT })
    }

    @Test
    fun `the branches of a split after the force do not handle the events that waited before it`() {
        splitAfterForce()
    }

    @Test
    fun `with TrueParallel the branches of a split after the force do not handle the events that waited before it`() {
        splitAfterForce(TrueParallelFeature::class)
    }

    @Test
    fun `a force while the engine is stopped counts the events on the bus at the start as handled`() {
        val run = run(channel = "orders")
        run.awaitStack(ROBOT, "asleep")

        run.engine.stop()
        run.send("orders", "go")
        run.force("ready", started = false)
        run.engine.start()
        run.awaitForced()
        run.awaitTicks(3)
        assertEquals(listOf(listOf("asleep"), listOf("ready")), run.stacksOf(ROBOT))

        run.send("orders", "go")
        run.awaitStack(ROBOT, "moving")
    }

    @Test
    fun `with EventRecording an event sent before the force counts as handled as well`() {
        val run = run(channel = "orders", features = arrayOf(EventRecordingFeature::class))
        run.engine.featureManager.onMessage(EventRecordingFeature::class, "start")
        run.awaitStack(ROBOT, "asleep")

        run.engine.stop()
        run.send("orders", "go")
        run.force("ready", started = false)
        run.engine.start()
        run.awaitForced()
        run.awaitTicks(3)
        assertEquals(listOf(listOf("asleep"), listOf("ready")), run.stacksOf(ROBOT))

        run.send("orders", "go")
        run.awaitStack(ROBOT, "moving")
    }

    /**
     * Forces `robot` into `ready` while `go` waits, splits it and checks that neither branch fires on that `go`, and
     * that the left branch fires on the next one.
     */
    private fun splitAfterForce(vararg features: KClass<out Feature>) {
        val run = run(channel = "orders", robot = SPLITTING_ROBOT, features = features)
        run.awaitStack(ROBOT, "asleep")

        run.send("orders", "go")
        run.force("ready")
        run.send("split", "now")
        run.awaitStack(ROBOT, "left", "right")
        run.awaitTicks(3)
        assertEquals(listOf(listOf("asleep"), listOf("ready"), listOf("left", "right")), run.stacksOf(ROBOT))

        run.send("orders", "go")
        run.awaitStack(ROBOT, "left done", "right")
    }

    /**
     * Starts an engine with `robot` running [robot] and `watcher` waiting for `go` on [channel], both with `robot`
     * listening on [channel] as well, and [features] on.
     */
    private fun run(
        channel: String,
        robot: String = ROBOT_MACHINE,
        features: Array<out KClass<out Feature>> = emptyArray()
    ): Run {
        val manager = FeatureManager()
        features.forEach { manager.requestFeatureActivation(it) }
        val metamodel = KmetaDSLConverter.parseKmetaString(METAMODEL)
        val engineId = "forced-${COUNTER.incrementAndGet()}"
        val engine = Engine(
            metamodel = metamodel,
            model = KmodelDSLConverter.parseKmodelString(MODEL, metamodel),
            statemachineMap = mapOf(
                ROBOT to KstatesDSLConverter.parseKstatesString(robot.replace(CHANNEL, channel)),
                WATCHER to KstatesDSLConverter.parseKstatesString(WATCHER_MACHINE.replace(CHANNEL, channel))
            ),
            tickDelayMS = 1,
            eventBus = EventBus(),
            engineId = engineId,
            traceLogger = EngineTraceLogger(engineId, consoleOutput = false),
            featureManager = manager
        )
        engines += engine
        return Run(engine).also { engine.start() }
    }

    /** A started engine with the stacks and the trace it reported. */
    private class Run(val engine: Engine) {

        /** Every trace entry so far, in order. */
        private val entries = CopyOnWriteArrayList<EngineTraceLogger.TraceEntry>()

        /** Every reported active stack, with the key of its object, in order. */
        private val reported = CopyOnWriteArrayList<Pair<String, List<String>>>()

        init {
            engine.traceLogger!!.addTraceListener { entries += it }
            engine.onActiveStackChanged = { id, stack -> reported += id to stack }
        }

        /** The reported stacks of [id], in order. */
        fun stacksOf(id: String): List<List<String>> = reported.filter { it.first == id }.map { it.second }

        /** The entries of forced states, in order. */
        fun forcedEntries() = entries.filter {
            it.eventType == TraceEventType.INITIAL_STATE && it.message.startsWith("Forced state")
        }

        /** Sends the event [name] on [channel] from outside. */
        fun send(channel: String, name: String) = engine.receiveExternalEvent(Event(channel, name))

        /** Forces `robot` into [state] and, when the engine runs ([started]), waits until the engine applied it. */
        fun force(state: String, started: Boolean = true) {
            engine.forceActiveState(ROBOT, state)
            if (started) awaitForced()
        }

        /** Waits until the engine applied a forced state. */
        fun awaitForced() = await("the forced state") { forcedEntries().isNotEmpty() }

        /** Waits until [count] more ticks of `robot` have ended. */
        fun awaitTicks(count: Int) {
            val reached = robotTicks()
            await("$count more ticks") { robotTicks() >= reached + count }
        }

        /** Waits until the last reported stack of [id] is [states], the stacks of all its branches one after the other. */
        fun awaitStack(id: String, vararg states: String) = await("the stack ${states.toList()} of $id") {
            stacksOf(id).lastOrNull() == states.toList()
        }

        private fun robotTicks(): Int =
            entries.count { it.eventType == TraceEventType.TICK_END && it.modelElementId == ROBOT }

        private fun await(what: String, reached: () -> Boolean) {
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

        /** Gives every engine an id of its own. */
        val COUNTER = AtomicInteger()

        const val ROBOT = "robot"
        const val WATCHER = "watcher"

        /** Stands for the channel of `go` in the state machines below. */
        const val CHANNEL = "CHANNEL"

        val METAMODEL = """
            type "Site" "Where the robot and the watcher are" {
                has("robot", "Robot")
                has("watcher", "Watcher")
            }
            type "Robot" "A robot" {
            }
            type "Watcher" "Watches the orders of the robot" {
            }
        """.trimIndent()

        val MODEL = """
            make object "site":"Site" {
                has("robot") -> make object "robot":"Robot" { }
                has("watcher") -> make object "watcher":"Watcher" { }
            }
        """.trimIndent()

        /** Rests in `asleep`, which no event leaves; `ready` is reached only by force and left on `go`. */
        val ROBOT_MACHINE = """
            STATEMACHINE ATTACHED TO "Robot" {
                STATES {
                    INITIAL STATE "asleep" { }
                    STATE "ready" { }
                    STATE "moving" { }
                }
                TRANSITIONS {
                    TRANSITION "ready" -> "moving" { CONDITION { EVENT("CHANNEL", "go") } }
                }
            }
        """.trimIndent()

        /** As [ROBOT_MACHINE], but `ready` splits on `split` and the left branch goes on on `go`. */
        val SPLITTING_ROBOT = """
            STATEMACHINE ATTACHED TO "Robot" {
                STATES {
                    INITIAL STATE "asleep" { }
                    STATE "ready" { }
                    STATE "left" { }
                    STATE "left done" { }
                    STATE "right" { }
                }
                TRANSITIONS {
                    SPLIT "ready" -> "left", "right" { CONDITION { EVENT("split", "now") } }
                    TRANSITION "left" -> "left done" { CONDITION { EVENT("CHANNEL", "go") } }
                }
            }
        """.trimIndent()

        /** Wakes on `now` of the channel `wake` and then waits for `go`. */
        val WATCHER_MACHINE = """
            STATEMACHINE ATTACHED TO "Watcher" {
                STATES {
                    INITIAL STATE "asleep" { }
                    STATE "waiting" { }
                    STATE "seen" { }
                }
                TRANSITIONS {
                    TRANSITION "asleep" -> "waiting" { CONDITION { EVENT("wake", "now") } }
                    TRANSITION "waiting" -> "seen" { CONDITION { EVENT("CHANNEL", "go") } }
                }
            }
        """.trimIndent()
    }
}
