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

import instance.Model
import meta.Metamodel
import states.StateMachine
import states.Transition
import states.conditions.ConditionType
import states.conditions.EvalCondition
import states.conditions.EventCondition
import states.conditions.ValueCondition

class TransitionProcessor(
    val stateMachine: StateMachine,
    val metamodel: Metamodel,
    val model: Model,
    val stateMachineAttachedToModelElement: String,
    val macroProcessor: MacroProcessor,
    val stateMachineQueryHelper: StateMachineQueryHelper,
    val modelQueryProcessor: ModelQueryProcessor,
    val eventProcessor: EventProcessor
) {

    val transitions = stateMachine.transitions

    /**
     * Finds the first transition that can be executed from [currentState].
     *
     * Evaluation order per transition:
     * 1. [ConditionType.EVENT]  – checks the [EventProcessor] (shared EventBus) for the event.
     * 2. [ConditionType.EVAL]   – executes the inline macro code and expects a boolean result.
     * 3. [ConditionType.VALUE]  – compares a model property value against a literal.
     *
     * When a matching transition is found for an EVENT condition the event is consumed
     * (marked as processed by this engine) so that the same engine won't react to it twice.
     *
     * @param currentState The name of the active state.
     * @return The first executable [Transition], or null if none applies.
     */
    fun findFirstExecutableTransition(currentState: String): Transition? {
        val candidates = transitions.filter { t ->
            t.fromState == currentState && (t.allowLoops || t.fromState != t.toState)
        }

        for (transition in candidates) {
            val condition = transition.condition
            val fires = when (condition.conditionType) {

                ConditionType.EVENT -> {
                    val ec = condition as EventCondition
                    if (eventProcessor.hasEvent(ec.eventDomain, ec.eventValue)) {
                        // Consume the event so we don't react to it a second time
                        eventProcessor.consumeEvent(ec.eventDomain, ec.eventValue)
                        true
                    } else {
                        false
                    }
                }

                ConditionType.EVAL -> {
                    val ec = condition as EvalCondition
                    try {
                        val result = macroProcessor.executeInlineMacro(ec.code, "boolean")
                        result == true
                    } catch (e: Exception) {
                        System.err.println("[TransitionProcessor] EVAL condition failed: ${e.message}")
                        false
                    }
                }

                ConditionType.VALUE -> {
                    val vc = condition as ValueCondition
                    try {
                        evaluateValueCondition(vc)
                    } catch (e: Exception) {
                        System.err.println("[TransitionProcessor] VALUE condition failed: ${e.message}")
                        false
                    }
                }
            }

            if (fires) return transition
        }
        return null
    }

    /**
     * Evaluates a [ValueCondition] by resolving the boolean variable path against the context
     * model element. The path must resolve to a Boolean value.
     */
    private fun evaluateValueCondition(vc: ValueCondition): Boolean {
        val context = modelQueryProcessor.getDataObjectById(stateMachineAttachedToModelElement)
        val resolved = modelQueryProcessor.resolvePathFromObject(context, vc.boolVariable)
        return when (resolved) {
            is Boolean -> resolved
            is String  -> resolved.lowercase() == "true"
            else       -> false
        }
    }
}