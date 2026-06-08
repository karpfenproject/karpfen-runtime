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

import dsl.textual.JsonToModelConverter
import dsl.textual.KmetaDSLConverter
import instance.DataObject
import io.karpfen.io.karpfen.exec.*
import io.karpfen.io.karpfen.messages.EventBus
import states.actions.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the runtime mechanics that make a scoped event readable:
 * - `event->...` path resolution against the event in scope
 * - IF IN SCOPE blocks running only when their paths are available
 *
 * These avoid Python by using literal SETs and direct path resolution.
 */
class EventScopeTest {

    private lateinit var mqp: ModelQueryProcessor
    private lateinit var eventProcessor: EventProcessor
    private lateinit var macroProcessor: MacroProcessor
    private lateinit var setSpeedPayload: DataObject

    @BeforeEach
    fun setUp() {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        mqp = ModelQueryProcessor(metamodel, model)
        eventProcessor = EventProcessor("test-engine", EventBus())
        macroProcessor = MacroProcessor(metamodel, model, emptyList(), "turtle", mqp)

        val eventMeta = KmetaDSLConverter.parseKmetaString(
            """
            type "setSpeed" "Change driving speed" {
                prop("factor", "number")
            }
            """.trimIndent(),
            metamodel.types
        )
        setSpeedPayload = JsonToModelConverter(eventMeta).toDataObject("""{"factor": 7.0}""", "setSpeed")
    }

    private fun makeActionProcessor(): ActionProcessor =
        ActionProcessor(macroProcessor, mqp, eventProcessor, "turtle")

    @Test
    fun `event path resolves against the scoped event`() {
        val turtle = mqp.getDataObjectById("turtle")
        assertEquals(7.0, mqp.resolvePathWithEvent(turtle, setSpeedPayload, "event->factor"))
    }

    @Test
    fun `event path without an event in scope is not available`() {
        val turtle = mqp.getDataObjectById("turtle")
        assertNull(mqp.tryResolvePathWithEvent(turtle, null, "event->factor"))
    }

    @Test
    fun `IF IN SCOPE block runs only when the event is in scope`() {
        val ap = makeActionProcessor()
        val block = ActionBlock(mutableListOf<ActionItem>(
            InScopeBlock(
                listOf("event->factor"),
                ActionBlock(mutableListOf<ActionItem>(
                    ActionRule(ActionOperationType.SET, "d_closest_wall", ValueActionRightSide("5.0"))
                ))
            )
        ))

        // No event in scope → the inner SET is skipped, the value stays at its initial 100.0
        ap.executeBlock(block, null)
        assertEquals(100.0, mqp.getDataObjectById("turtle").getProp("d_closest_wall").first())

        // Event in scope → the inner SET runs
        ap.executeBlock(block, setSpeedPayload)
        assertEquals(5.0, mqp.getDataObjectById("turtle").getProp("d_closest_wall").first())
    }
}
