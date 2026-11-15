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
package io.karpfen.env

import io.karpfen.EngineTraceLogger.TraceEntry
import io.karpfen.EngineTraceLogger.TraceEventType
import io.karpfen.fixtures.Eventually
import io.karpfen.fixtures.RuntimeRegistry
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.server.APIService
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A run that changes its state machines replays to the same trace: the events EventRecording recorded in one
 * environment, uploaded to EventInjection of a fresh environment with the same uploads, give the same trace entries
 * line by line, apart from the time and the key of the environment. The run uses `behaves after` at the start,
 * `EXCHANGE`, a split and a join; a variant adds `ATTACH` on an object a rule creates, which needs Python.
 */
class TraceReplayTest {

    @BeforeEach
    fun resetBefore() {
        RuntimeRegistry.reset()
    }

    @AfterEach
    fun resetAfter() {
        RuntimeRegistry.reset()
    }

    @Test
    fun `a recorded run with behaves after and EXCHANGE replays to the same trace`() {
        assertReplays(ROOM)
    }

    @Test
    @Tag("python")
    fun `a recorded run that also creates an object with ATTACH replays to the same trace`() {
        assertReplays(CREATING_ROOM)
    }

    /** Records a run of [room] with three presses, replays it in a fresh environment and compares the traces. */
    private fun assertReplays(room: String) {
        val recorded = createEnvironment(room)
        APIService.activateFeature(recorded, "EventRecording")
        APIService.sendMessage(recorded, "EventRecording", "start")
        val original = launch(recorded)
        val thread = assertNotNull(EnvironmentHandler.executionThreads[recorded])

        thread.acceptExternalEvent(Event("switch", "press"))
        Eventually.awaitUntil("the first exchange") { original.count(TraceEventType.STATE_MACHINE_EXCHANGED) == 1 }
        Eventually.awaitUntil("the first join") { original.count(TraceEventType.JOIN_FIRED) == 1 }
        thread.acceptExternalEvent(Event("switch", "press"))
        Eventually.awaitUntil("the way back") {
            original.entries.any { it.eventType == TraceEventType.TRANSITION_FIRED && it.details["to"] == "waiting" }
        }
        thread.acceptExternalEvent(Event("switch", "press"))
        Eventually.awaitUntil("the second join") { original.count(TraceEventType.JOIN_FIRED) == 2 }
        val settled = original.lastTick() + 2
        Eventually.awaitUntil("tick $settled") { original.lastTick() > settled }
        APIService.stopEnvironment(recorded)
        val lastTick = thread.engine.tickCount
        val recording = APIService.sendMessage(recorded, "EventRecording", "get")
        assertEquals(3, JSONObject(recording).keySet().size, "the ticks of the presses in $recording")

        val replayed = createEnvironment(room)
        APIService.activateFeature(replayed, "EventInjection")
        APIService.sendMessage(replayed, "EventInjection", "upload:$recording")
        APIService.sendMessage(replayed, "EventInjection", "start")
        val replay = launch(replayed)
        Eventually.awaitUntil("tick ${lastTick + 1} of the replay") { replay.lastTick() > lastTick }
        APIService.stopEnvironment(replayed)

        val expected = original.lines { it.eventType == TraceEventType.ENGINE_STOP }
        val actual = replay.lines { it.eventType == TraceEventType.TICK_START && it.message == "Tick #${lastTick + 1}" }
        assertTrue(expected.any { it.contains("STATE_MACHINE_EXCHANGED") })
        assertEquals(expected.size, actual.size, "the number of entries")
        expected.zip(actual).forEachIndexed { index, (want, got) -> assertEquals(want, got, "entry $index") }
    }

    /** Creates an environment with the uploads of every run of this test and [room] attached to the room. */
    private fun createEnvironment(room: String): String {
        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, METAMODEL)
        APIService.receiveModel(envKey, MODEL)
        APIService.receiveStateMachine(envKey, "room", room)
        APIService.receiveStateMachine(envKey, null, GLOWING)
        APIService.receiveStateMachine(envKey, null, BLINKING)
        APIService.updateTickDelay(envKey, TICK_DELAY_MS)
        return envKey
    }

    /** Activates and starts [envKey] and collects its trace from the first entry on. */
    private fun launch(envKey: String): Trace {
        APIService.runEnvironment(envKey)
        val trace = Trace()
        assertNotNull(EnvironmentHandler.executionThreads[envKey]).engine.traceLogger!!.addTraceListener { trace.entries += it }
        APIService.startEnvironment(envKey)
        return trace
    }

    /** The trace entries of one environment. */
    private class Trace {

        /** Every entry so far, in order. */
        val entries = CopyOnWriteArrayList<TraceEntry>()

        /** The number of entries of [type] so far. */
        fun count(type: TraceEventType): Int = entries.count { it.eventType == type }

        /** The number of the latest tick that started, or 0 before the first. */
        fun lastTick(): Long = entries.filter { it.eventType == TraceEventType.TICK_START }
            .maxOfOrNull { it.message.removePrefix("Tick #").toLong() } ?: 0

        /**
         * The entries before the first one that is [end], each as its object, type, message and details, without the
         * time and the key of the environment.
         */
        fun lines(end: (TraceEntry) -> Boolean): List<String> {
            val all = entries.toList()
            val stop = all.indexOfFirst(end).let { if (it < 0) all.size else it }
            return all.subList(0, stop).map { entry ->
                "[${entry.modelElementId}] ${entry.eventType}: ${entry.message} | " +
                    entry.details.entries.joinToString(", ") { "${it.key}=${it.value}" }
            }
        }
    }

    private companion object {
        /** The tick delay of both environments. */
        const val TICK_DELAY_MS = 5

        val METAMODEL = """
            type "Room" "A room with a lamp and spare bulbs" {
                has("lamp", "Lamp")
                has("spares", list("Bulb"))
            }
            type "Lamp" "A lamp with a bulb" {
                has("bulb", "Bulb")
            }
            type "Bulb" "A light bulb" behaves after "glowing" {
                prop("log", list("string"))
            }
        """.trimIndent()

        val MODEL = """
            make object "room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" {
                    has("bulb") -> make object "bulb":"Bulb" { }
                }
                has("spares") -> make object "spare":"Bulb" { }
            }
        """.trimIndent()

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

        val BLINKING = """
            STATEMACHINE "blinking" ATTACHABLE TO "Bulb" {
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

        /** On a press exchanges the state machine of the bulb, splits, joins, and on the next press waits again. */
        val ROOM = room("""ENTRY { EXCHANGE("lamp->bulb", "blinking") }""")

        /** Like [ROOM], and also adds a spare bulb that blinks. Needs Python. */
        val CREATING_ROOM = room(
            """ENTRY {
                EXCHANGE("lamp->bulb", "blinking")
                APPENDOBJ("self", "spares", EVAL { return {"log": []} }) ATTACH "blinking"
            }"""
        )

        fun room(swap: String) = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "waiting" { }
                    STATE "swap" { $swap }
                    STATE "left" { }
                    STATE "right" { }
                    STATE "done" { }
                }
                TRANSITIONS {
                    TRANSITION "waiting" -> "swap" { CONDITION { EVENT("switch", "press") } }
                    SPLIT "swap" -> "left", "right" { }
                    JOIN "left", "right" -> "done" { }
                    TRANSITION "done" -> "waiting" { CONDITION { EVENT("switch", "press") } }
                }
            }
        """.trimIndent()
    }
}
