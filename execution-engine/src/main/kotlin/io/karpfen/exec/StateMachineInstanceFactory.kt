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

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.metrics.RunMetricsRecorder

/**
 * Builds the execution context of one state machine instance, at its initial stack, from an [InstanceSpec].
 *
 * Every call builds everything mutable anew: the query helper, the macro processor with its own Python sessions, the
 * transition and action processors and the first branch. The identities under which the branches consume events start
 * with `<engineId>-<objectId>#<n>`, where `n` counts the instances of the object from 0 over the life of the engine, so
 * an instance that replaces another on the same object does not count the events of its predecessor as handled, and
 * end in `-p<path>`, the path number of the branch. The factory owns the [PathNumbers] of the engine and gives them to
 * every context it builds, so the path numbers of an object go on from one instance to the next. The factory is used
 * by the engine thread only.
 *
 * @property metamodel the metamodel of the model.
 * @property model the model the instances read and change.
 * @property modelQueryProcessor the shared access to the model.
 * @property eventBus the bus of the environment.
 * @property engineId the id of the engine, the start of every branch identity.
 * @property metrics where the macro processors of the instances report their calls of code.
 */
class StateMachineInstanceFactory(
    private val metamodel: Metamodel,
    private val model: Model,
    private val modelQueryProcessor: ModelQueryProcessor,
    private val eventBus: EventBus,
    private val engineId: String,
    private val metrics: RunMetricsRecorder = RunMetricsRecorder.NONE
) {

    /** The number of instances built per object. */
    private val instanceCounts = HashMap<String, Int>()

    /** The path numbers of all branches of the engine. */
    private val pathNumbers = PathNumbers()

    /**
     * Builds the context of [spec] at the initial stack of its state machine, with one branch whose whole stack still
     * needs entering. [attachments] is what its actions use for `ATTACH` and `EXCHANGE`.
     *
     * @throws IllegalStateException when the state machine has no initial state.
     * @throws IllegalArgumentException when the object of [spec] is not in the model.
     */
    fun build(spec: InstanceSpec, attachments: StateMachineAttachments): SMContext {
        val objectId = spec.objectId
        val stateMachine = spec.stateMachine
        val smQueryHelper = StateMachineQueryHelper(stateMachine)
        val initialStack = smQueryHelper.getInitialStateStack()
        check(initialStack.isNotEmpty()) { "State machine attached to '$objectId' has no initial state" }

        val macroProcessor = MacroProcessor(metamodel, model, stateMachine.macros, objectId, modelQueryProcessor, metrics)
        val transitionProcessor = TransitionProcessor(
            stateMachine, metamodel, model, objectId, macroProcessor, smQueryHelper, modelQueryProcessor
        )
        val number = instanceCounts.merge(objectId, 1, Int::plus)!! - 1
        val branchIdBase = "$engineId-$objectId#$number"
        // The action processor only publishes internal events, so its identity is not used for consumption;
        // consumption is tracked per branch via each branch's own event processor.
        val actionProcessor = ActionProcessor(
            macroProcessor,
            modelQueryProcessor,
            EventProcessor("$branchIdBase-actions", eventBus),
            objectId,
            attachments
        )

        val ctx = SMContext(
            modelElementId = objectId,
            stateMachine = stateMachine,
            smQueryHelper = smQueryHelper,
            transitionProcessor = transitionProcessor,
            actionProcessor = actionProcessor,
            eventBus = eventBus,
            branchIdBase = branchIdBase,
            branches = mutableListOf(),
            definitionName = spec.definitionName,
            macroProcessor = macroProcessor,
            pathNumbers = pathNumbers
        )
        ctx.branches = mutableListOf(ctx.newBranch(initialStack, initialStack.toMutableList(), null, emptySet()))
        return ctx
    }
}
