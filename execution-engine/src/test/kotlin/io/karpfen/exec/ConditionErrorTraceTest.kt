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
import io.karpfen.io.karpfen.messages.EventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An error in a condition goes to the trace log as `CONDITION_ERROR`, like an error in an action goes there as
 * `ACTION_ERROR`, and the condition is false. The condition here reads a property the lamp does not have, so no Python
 * runs.
 */
class ConditionErrorTraceTest {

    private var engine: Engine? = null

    @AfterEach
    fun close() {
        engine?.close()
    }

    @Test
    fun `a condition that fails is false and its error is in the trace log with its state`() {
        val entries = start(LAMP)
        PythonFixtures.await("a condition error") { entries.any { it.eventType == TraceEventType.CONDITION_ERROR } }
        PythonFixtures.await("the state after the other transition") {
            entries.any { it.eventType == TraceEventType.STATE_ENTRY_EXEC && it.details["state"] == "next" }
        }

        val error = entries.first { it.eventType == TraceEventType.CONDITION_ERROR }
        assertEquals("a", error.modelElementId)
        assertTrue(error.message.startsWith("Error in a condition of a transition from 'start': "), error.message)
        assertEquals(mapOf("path" to "0", "state" to "start", "stack" to "start"), error.details)
        assertTrue(entries.none { it.details["state"] == "wrong" }, "a condition that fails is false")
        assertTrue(engine!!.traceLogger!!.errors().any { it.eventType == TraceEventType.CONDITION_ERROR })
    }

    @Test
    fun `the error of a condition of a join names its source states and the paths it would join`() {
        val entries = start(SPLITTING_LAMP)
        PythonFixtures.await("a condition error") { entries.any { it.eventType == TraceEventType.CONDITION_ERROR } }

        val error = entries.first { it.eventType == TraceEventType.CONDITION_ERROR }
        assertTrue(error.message.startsWith("Error in a condition of a transition from 'left,right': "), error.message)
        assertEquals(mapOf("paths" to "1,2", "state" to "left,right"), error.details)
    }

    /** Starts an engine with the state machine [text] on the lamp `a` and gives the list its trace log fills. */
    private fun start(text: String): List<EngineTraceLogger.TraceEntry> {
        val metamodel = PythonFixtures.metamodel()
        val entries = CopyOnWriteArrayList<EngineTraceLogger.TraceEntry>()
        val engine = Engine(
            metamodel = metamodel,
            model = PythonFixtures.model(metamodel),
            statemachineMap = mapOf("a" to PythonFixtures.stateMachine(text)),
            tickDelayMS = 1,
            eventBus = EventBus(),
            traceLogger = EngineTraceLogger("condition-errors", consoleOutput = false)
        ).also { engine = it }
        engine.traceLogger!!.addTraceListener { entries += it }
        engine.start()
        return entries
    }

    private companion object {
        /** Splits into `left` and `right`, whose join reads a property the lamp does not have. */
        val SPLITTING_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "left" { }
                    STATE "right" { }
                    STATE "end" { }
                }
                TRANSITIONS {
                    SPLIT "start" -> "left", "right" { }
                    JOIN "left", "right" -> "end" { CONDITION { VALUE("missing->flag") } }
                }
            }
        """

        /** Leaves `start` by its second transition, because the condition of the first reads a missing property. */
        val LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "wrong" { ENTRY { APPEND("log", "wrong") } }
                    STATE "next" { ENTRY { APPEND("log", "next") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "wrong" { CONDITION { VALUE("missing->flag") } }
                    TRANSITION "start" -> "next" { }
                }
            }
        """
    }
}
