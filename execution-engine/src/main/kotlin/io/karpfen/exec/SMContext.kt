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
import io.karpfen.dsl.states.StateMachine
import io.karpfen.dsl.states.TransitionLike

/**
 * The execution context of one instance of a state machine: one state machine running on one object.
 *
 * It owns the shared, branch-agnostic services (transition evaluation, action execution, queries) and
 * a list of [branches]. A *simple* context has exactly one branch; a *parallel* context, produced by a
 * split, has several. The mode is therefore fully determined by the branch count; there is never any
 * separate flag to keep in sync.
 *
 * Every context is built for its instance alone by [StateMachineInstanceFactory]: its helpers, its macro processor
 * with its Python sessions, its branches and the identities under which its branches consume events belong to no
 * other context, also not to another instance of the same definition on another object or to an earlier instance on
 * the same object. Only the parsed [stateMachine] is shared, and it is never changed.
 *
 * A context ends once ([end]) when its object leaves the model or `EXCHANGE` replaces its state machine. From then on
 * it does no further step: a branch that runs an action block finishes that block and stops, and the engine skips the
 * context until the [StateMachineLifecycle] takes it out of the order at the end of the tick.
 *
 * @property modelElementId The id of the model object this state machine is attached to.
 * @property definitionName The name of the attachable state machine of this instance, or null for a state machine
 *   that the run configuration attached to the object.
 * @property macroProcessor The macro processor of this instance; closed when the instance leaves or the engine stops.
 * @property eventBus The shared event bus; used to mint per-branch event processors.
 * @property branchIdBase Stable prefix for branch event-processor ids, unique for this instance within one run.
 * @property pathNumbers Hands out the path number of every branch this context builds; shared by all instances of one
 *   engine, so the numbers on one object go on across instances.
 */
class SMContext(
    val modelElementId: String,
    val stateMachine: StateMachine,
    val smQueryHelper: StateMachineQueryHelper,
    val transitionProcessor: TransitionProcessor,
    val actionProcessor: ActionProcessor,
    private val eventBus: EventBus,
    private val branchIdBase: String,
    var branches: MutableList<Branch>,
    val definitionName: String? = null,
    val macroProcessor: MacroProcessor? = null,
    private val pathNumbers: PathNumbers = PathNumbers()
) {

    /**
     * True once the instance has ended. Set by the thread that removes its object or exchanges its state machine, read
     * by the engine thread and the threads of its branches, hence volatile.
     */
    @Volatile
    var hasEnded: Boolean = false
        private set

    /**
     * The stack last reported to [io.karpfen.Engine.onActiveStackChanged], or null before the first report. Kept per
     * instance, so a new instance on the same object reports its first stack also when it equals the last one of the
     * instance before it. Used by the engine thread only.
     */
    var lastReportedStack: List<String>? = null

    /** A dedicated event processor used only to evaluate join conditions at the context level. */
    val joinEventProcessor: EventProcessor = EventProcessor("$branchIdBase-join", eventBus)

    /** Ends this instance; see the class description. Ending an ended instance changes nothing. */
    fun end() {
        hasEnded = true
    }
    /** True when the machine is currently split across several parallel branches. */
    fun isParallel(): Boolean = branches.size > 1

    /** The path numbers of the current branches, in their order, separated by commas. */
    fun pathList(): String = branches.joinToString(",") { it.path.toString() }

    /**
     * The trace detail that names the paths of the instance as it stands: `path` with the number of its only branch,
     * or `paths` with the numbers of all branches while it is split.
     */
    fun pathDetail(): Pair<String, String> = if (branches.size == 1) "path" to pathList() else "paths" to pathList()

    /**
     * Creates a new branch on the next path number of the object, with its own event-processor identity, which ends in
     * `-p<path>`. [inheritedIds] seeds the branch's
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
        val path = pathNumbers.next(modelElementId)
        val processor = EventProcessor("$branchIdBase-p$path", eventBus, inheritedIds)
        return Branch(processor, path, stateStack, notEnteredSubstack, lastFiredTransition)
    }
}
