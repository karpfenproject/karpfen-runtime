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
import io.karpfen.dsl.reader.ParseResult
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.runtime.event.EventInjectionFeature
import io.karpfen.io.karpfen.messages.EventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the example of the reference section "State machine management and lifecycle" (3.1.13) with presses injected
 * before ticks 3 and 6, and checks its table of ticks. The macro that builds the spare bulb runs in Python.
 */
@Tag("python")
class LifecycleReferenceExampleTest {

    private var engine: Engine? = null

    @AfterEach
    fun stop() {
        engine?.close()
    }

    @Test
    fun `the room adds a blinking spare bulb and makes the bulb of the lamp blink`() {
        val entries = CopyOnWriteArrayList<EngineTraceLogger.TraceEntry>()
        val run = start(entries)

        await { entries.any { it.eventType == TraceEventType.TICK_START && it.message == "Tick #9" } }
        run.stop()

        fun steps(tick: Int) = entries.filter { it.eventType == TraceEventType.TICK_START && it.message == "Tick #$tick" }
            .map { it.modelElementId }
        fun tickOf(type: TraceEventType, objectId: String): Int {
            val index = entries.indexOfFirst { it.eventType == type && it.modelElementId == objectId }
            return entries.subList(0, index).last { it.eventType == TraceEventType.TICK_START }
                .message.removePrefix("Tick #").toInt()
        }

        assertEquals(listOf("living room", "bulb"), steps(4))
        assertEquals(listOf("living room", "bulb", "gen_Bulb_0"), steps(5))
        assertEquals(4, tickOf(TraceEventType.STATE_MACHINE_ATTACHED, "gen_Bulb_0"))
        assertEquals(listOf("living room", "gen_Bulb_0"), steps(7))
        assertEquals(7, tickOf(TraceEventType.STATE_MACHINE_DETACHED, "bulb"))
        assertEquals(listOf("living room", "bulb", "gen_Bulb_0"), steps(8))
        assertEquals(
            listOf("bulb" to "glowing", "gen_Bulb_0" to "blinking", "bulb" to "blinking"),
            entries.filter { it.eventType == TraceEventType.STATE_MACHINE_ATTACHED }
                .map { it.modelElementId to it.details.getValue("stateMachine") }
                .drop(1)
        )
        assertTrue(entries.none { it.eventType == TraceEventType.ACTION_ERROR }, "errors in $entries")
    }

    private fun start(entries: MutableList<EngineTraceLogger.TraceEntry>): Engine {
        val metamodel = accept(KmetaReader().read(METAMODEL))
        val model = accept(KmodelReader(metamodel).read(MODEL))
        val read = { text: String -> accept(KstatesReader().read(text)) }
        val features = FeatureManager()
        features.requestFeatureActivation(EventInjectionFeature::class)
        val press = """{"environmentKey": "switch", "messageType": "press", "payload": ""}"""
        features.onMessage(EventInjectionFeature::class, "upload:{\"3\": [$press], \"6\": [$press]}")
        features.onMessage(EventInjectionFeature::class, "start")
        val trace = EngineTraceLogger("reference", consoleOutput = false).also { it.addTraceListener { e -> entries += e } }
        return Engine(
            metamodel = metamodel,
            model = model,
            statemachineMap = mapOf("living room" to read(ROOM)),
            tickDelayMS = 1,
            eventBus = EventBus(),
            engineId = "reference",
            traceLogger = trace,
            featureManager = features,
            stateMachineDefinitions = listOf(GLOWING, BLINKING).map(read).associateBy { it.name!! }
        ).also { engine = it; it.start() }
    }

    private fun <M : Any> accept(result: ParseResult<M>): M {
        assertTrue(!result.hasErrors, "errors: ${result.errors}")
        return result.model!!
    }

    private fun await(reached: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000L
        while (!reached()) {
            if (System.currentTimeMillis() > deadline) fail("The example did not reach tick 9")
            Thread.sleep(5)
        }
    }

    private companion object {
        val METAMODEL = """
            type "Bulb" "A light bulb that burns with watts, up to maxWatts" behaves after "glowing" {
                prop("maxWatts", "number")
                prop("watts", "number")
                prop("lit", "boolean")
            }
            type "Switch" "A wall switch" {
                prop("position", "string")
                prop("presses", list("string"))
            }
            type "Lamp" "A lamp with a bulb, wired to a switch" {
                prop("mode", "string")
                has("bulb", "Bulb")
                knows("control", "Switch")
            }
            type "Room" "The room the lamp stands in, with spare bulbs" {
                has("lamp", "Lamp")
                has("switch", "Switch")
                has("spares", list("Bulb"))
            }
        """.trimIndent()

        val MODEL = """
            make object "living room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" {
                    prop("mode") -> "off"
                    has("bulb") -> make object "bulb":"Bulb" {
                        prop("maxWatts") -> "40"
                        prop("watts") -> "0"
                        prop("lit") -> "false"
                    }
                    knows("control") -> "wallSwitch"
                }
                has("switch") -> make object "wallSwitch":"Switch" {
                    prop("position") -> "down"
                }
            }
        """.trimIndent()

        val GLOWING = """
            STATEMACHINE "glowing" ATTACHABLE TO "Bulb" {
                STATES {
                    INITIAL STATE "dark" {
                        ENTRY { SET("lit", "false") }
                    }
                    STATE "lit" {
                        ENTRY { SET("lit", "true") }
                    }
                }
                TRANSITIONS {
                    TRANSITION "dark" -> "lit" {
                        CONDITION { EVAL { return $(watts) > 0 } }
                    }
                    TRANSITION "lit" -> "dark" {
                        CONDITION { EVAL { return $(watts) == 0 } }
                    }
                }
            }
        """.trimIndent()

        val BLINKING = """
            STATEMACHINE "blinking" ATTACHABLE TO "Bulb" {
                STATES {
                    INITIAL STATE "off" {
                        ENTRY { SET("lit", "false") }
                    }
                    STATE "on" {
                        ENTRY { SET("lit", "true") }
                    }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "on" { }
                    TRANSITION "on" -> "off" { }
                }
            }
        """.trimIndent()

        val ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "waiting" { }
                    STATE "stocked" {
                        ENTRY {
                            APPENDOBJ("self", "spares", MACRO("new bulb", "lamp->bulb->maxWatts")) ATTACH "blinking"
                        }
                    }
                    STATE "party" {
                        ENTRY {
                            EXCHANGE("lamp->bulb", "blinking")
                        }
                    }
                }
                TRANSITIONS {
                    TRANSITION "waiting" -> "stocked" {
                        CONDITION { EVENT("switch", "press") }
                    }
                    TRANSITION "stocked" -> "party" {
                        CONDITION { EVENT("switch", "press") }
                    }
                }
                MACROS {
                    MACRO "new bulb" {
                        TAKES("watts", "number")
                        RETURNS("Bulb")
                        DEFINITION {
                            EVAL { return {"maxWatts": $(watts), "watts": $(watts)} }
                        }
                    }
                }
            }
        """.trimIndent()
    }
}
