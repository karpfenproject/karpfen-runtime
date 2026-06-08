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
package io.karpfen.io.karpfen.exec

import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import states.StateMachine
import states.TransitionLike

/**
 * The execution context of one attached state machine.
 *
 * It owns the shared, branch-agnostic services (transition evaluation, action execution, queries) and
 * a list of [branches]. A *simple* context has exactly one branch (today's behaviour); a *parallel*
 * context, produced by a split, has several. The mode is therefore fully determined by the branch
 * count — there is never any separate flag to keep in sync.
 *
 * @property modelElementId The id of the model object this state machine is attached to.
 * @property eventBus The shared event bus; used to mint per-branch event processors.
 * @property branchIdBase Stable prefix for branch event-processor ids.
 */
class SMContext(
    val modelElementId: String,
    val stateMachine: StateMachine,
    val smQueryHelper: StateMachineQueryHelper,
    val transitionProcessor: TransitionProcessor,
    val actionProcessor: ActionProcessor,
    private val eventBus: EventBus,
    private val branchIdBase: String,
    var branches: MutableList<Branch>
) {

    private var branchSeq: Int = 0

    /** A dedicated event processor used only to evaluate join conditions at the context level. */
    val joinEventProcessor: EventProcessor = EventProcessor("$branchIdBase-join", eventBus)

    /** True when the machine is currently split across several parallel branches. */
    fun isParallel(): Boolean = branches.size > 1

    /**
     * Creates a new branch with its own event-processor identity. [inheritedIds] seeds the branch's
     * consumption lineage (a split child inherits the parent's lineage; a join's branch inherits the
     * union of the joined branches' lineages), so a freshly created branch does not re-handle events
     * its ancestors already handled.
     */
    fun newBranch(
        stateStack: List<String>,
        notEnteredSubstack: MutableList<String>,
        lastFiredTransition: TransitionLike?,
        inheritedIds: Set<String>
    ): Branch {
        val processor = EventProcessor("$branchIdBase-b${branchSeq++}", eventBus, inheritedIds)
        return Branch(processor, stateStack, notEnteredSubstack, lastFiredTransition)
    }
}
