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

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
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

/**
 * Covers the commit of a notification batch that contains a change whose object cannot be written as JSON because a
 * `knows` relation made at run time leads back to it: the other changes and the deletions of the batch are still
 * published, the publishers learn about the unwritable change, and an action's own failure is not replaced.
 */
class ReferenceLoopCommitTest {

    /** The query processor over the holder `h` with the nodes `a`, `b`, `c` and `d`, where `a` knows `b`. */
    private lateinit var modelQueryProcessor: ModelQueryProcessor

    /** Every publication as kind and object id, in publishing order. */
    private val published = mutableListOf<String>()

    /** The publisher recording into [published]. */
    private val recorder = object : ModelChangePublisher {
        override fun onObjectChanged(objectId: String, jsonValue: String) {
            published.add("changed $objectId")
        }

        override fun onObjectDeleted(objectId: String) {
            published.add("deleted $objectId")
        }

        override fun onObjectUnwritable(objectId: String) {
            published.add("unwritable $objectId")
        }
    }

    /** Parses the holder model and registers the recording publisher. */
    @BeforeEach
    fun setUp() {
        val metamodel = KmetaDSLConverter.parseKmetaString(METAMODEL)
        modelQueryProcessor = ModelQueryProcessor(metamodel, KmodelDSLConverter.parseKmodelString(MODEL, metamodel))
        modelQueryProcessor.addChangePublisher(recorder)
    }

    @Test
    fun `a change that cannot be written leaves the other changes and the deletions of its batch published`() {
        modelQueryProcessor.beginBatch()
        modelQueryProcessor.setObjectRelation("b", "next", modelQueryProcessor.getDataObjectById("a"))
        modelQueryProcessor.setScalar("d", "v", 9.0)
        modelQueryProcessor.dropObject("c")

        val failure = assertFailsWith<IllegalStateException> { modelQueryProcessor.commitBatch() }

        assertEquals(listOf("unwritable b", "changed d", "unwritable h", "deleted c"), published)
        assertEquals("Reference loop through object 'b' cannot be written as JSON", failure.message)
        assertEquals(
            listOf("Reference loop through object 'a' cannot be written as JSON"),
            failure.suppressed.map { it.message }
        )
    }

    @Test
    fun `a failing action keeps its own error and carries the failed commit as suppressed`() {
        modelQueryProcessor.removeChangePublisher(recorder)
        modelQueryProcessor.setObjectRelation("b", "next", modelQueryProcessor.getDataObjectById("a"))
        modelQueryProcessor.addChangePublisher(recorder)
        val block = ActionBlock(mutableListOf<ActionItem>(
            ActionRule(ActionOperationType.SET, "v", ValueActionRightSide("1.0")),
            ActionRule(ActionOperationType.SET, "next", ValueActionRightSide("1.0"))
        ))

        val failure = assertFailsWith<IllegalArgumentException> { actionProcessorFor("a").executeBlock(block) }

        assertEquals("'next' on 'Node' is not a scalar property (use SETOBJ for relations)", failure.message)
        assertEquals(
            listOf("Reference loop through object 'a' cannot be written as JSON"),
            failure.suppressed.map { it.message }
        )
        assertEquals(listOf("unwritable a"), published)
    }

    @Test
    fun `a block whose only failure is the commit fails with the reference loop error`() {
        modelQueryProcessor.removeChangePublisher(recorder)
        modelQueryProcessor.setObjectRelation("b", "next", modelQueryProcessor.getDataObjectById("a"))
        modelQueryProcessor.addChangePublisher(recorder)
        val block = ActionBlock(mutableListOf<ActionItem>(
            ActionRule(ActionOperationType.SET, "v", ValueActionRightSide("1.0"))
        ))

        val failure = assertFailsWith<IllegalStateException> { actionProcessorFor("a").executeBlock(block) }

        assertEquals("Reference loop through object 'a' cannot be written as JSON", failure.message)
        assertEquals(listOf("unwritable a"), published)
    }

    /** Builds an action processor whose context is the object [contextObjectId]. */
    private fun actionProcessorFor(contextObjectId: String) = ActionProcessor(
        MacroProcessor(
            modelQueryProcessor.metamodel,
            modelQueryProcessor.model,
            emptyList(),
            contextObjectId,
            modelQueryProcessor
        ),
        modelQueryProcessor,
        EventProcessor("loop-test", EventBus()),
        contextObjectId
    )

    private companion object {
        /** Nodes that know the next node, embedded in a holder. */
        val METAMODEL = """
            type "Node" "A node that knows the next node" {
                prop("v", "number")
                knows("next", "Node")
            }
            type "Holder" "Embeds the nodes" {
                has("nodes", list("Node"))
            }
        """.trimIndent()

        /** The holder `h` with the chain `a` to `b` to `c` and the unrelated node `d`. */
        val MODEL = """
            make object "h":"Holder" {
                has("nodes") -> make object "a":"Node" {
                    prop("v") -> "0"
                    knows("next") -> "b"
                }
                has("nodes") -> make object "b":"Node" {
                    prop("v") -> "0"
                    knows("next") -> "c"
                }
                has("nodes") -> make object "c":"Node" {
                    prop("v") -> "0"
                }
                has("nodes") -> make object "d":"Node" {
                    prop("v") -> "0"
                }
            }
        """.trimIndent()
    }
}
