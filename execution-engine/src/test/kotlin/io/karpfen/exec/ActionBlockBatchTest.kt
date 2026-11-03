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

import io.karpfen.io.karpfen.exec.ActionProcessor
import io.karpfen.io.karpfen.exec.EventProcessor
import io.karpfen.io.karpfen.exec.MacroProcessor
import io.karpfen.io.karpfen.exec.ModelChangePublisher
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import io.karpfen.io.karpfen.messages.EventBus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import states.actions.ActionBlock
import states.actions.ActionItem
import states.actions.ActionOperationType
import states.actions.ActionRule
import states.actions.ValueActionRightSide
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the notification batch of [ActionProcessor.executeBlock] when an action of the block fails.
 */
class ActionBlockBatchTest {

    /** The fixture model's query processor, which batches the notifications of a block. */
    private lateinit var modelQueryProcessor: ModelQueryProcessor

    /** Runs blocks for `turtle`. */
    private lateinit var actionProcessor: ActionProcessor

    /** Every published change as object id and JSON, in publishing order. */
    private val published = mutableListOf<Pair<String, String>>()

    /** Builds the fixture model with a recording change publisher and an action processor for `turtle`. */
    @BeforeEach
    fun setUp() {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        modelQueryProcessor = ModelQueryProcessor(metamodel, model)
        modelQueryProcessor.addChangePublisher(ModelChangePublisher { objectId, jsonValue ->
            published.add(objectId to jsonValue)
        })
        actionProcessor = ActionProcessor(
            MacroProcessor(metamodel, model, emptyList(), "turtle", modelQueryProcessor),
            modelQueryProcessor,
            EventProcessor("batch-test", EventBus()),
            "turtle"
        )
    }

    @Test
    fun `changes made before a failing action are published when the block ends`() {
        val block = ActionBlock(mutableListOf<ActionItem>(
            ActionRule(ActionOperationType.SET, "d_closest_obstacle", ValueActionRightSide("42.0")),
            ActionRule(ActionOperationType.SET, "direction", ValueActionRightSide("1.0")),
            ActionRule(ActionOperationType.SET, "d_closest_wall", ValueActionRightSide("7.0"))
        ))

        assertFailsWith<IllegalArgumentException> { actionProcessor.executeBlock(block) }

        assertEquals(listOf("turtle"), published.map { it.first })
        assertTrue(published.single().second.contains("\"d_closest_obstacle\": 42"))
        assertTrue(published.single().second.contains("\"d_closest_wall\": 100"))
    }

    @Test
    fun `a failing block leaves no batch open for later writes`() {
        val failing = ActionBlock(mutableListOf<ActionItem>(
            ActionRule(ActionOperationType.SET, "direction", ValueActionRightSide("1.0"))
        ))
        assertFailsWith<IllegalArgumentException> { actionProcessor.executeBlock(failing) }
        published.clear()

        modelQueryProcessor.updateProperty("turtlePosition", "x", 9.0)

        assertEquals(listOf("turtlePosition"), published.map { it.first })
    }
}
