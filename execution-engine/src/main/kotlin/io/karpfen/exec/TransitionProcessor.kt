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
    val eventProcessor: EventProcessor,
    /** When true (default), events are only consumed when a transition fires. When false, consumed on condition read. */
    val eventConsumptionOnFire: Boolean = true
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
        return findAllExecutableTransitions(currentState).firstOrNull()
    }

    /**
     * Finds all transitions whose conditions evaluate to true for [currentState],
     * returned in definition order. Each transition's condition is evaluated lazily.
     *
     * **Important**: For EVENT conditions, the event is consumed (marked as processed)
     * as soon as the transition is yielded. Callers that iterate but don't fire a
     * transition should be aware of this side-effect.
     *
     * @param currentState The name of the active state.
     * @return A sequence of executable transitions in definition order.
     */
    fun findAllExecutableTransitions(currentState: String): Sequence<Transition> = sequence {
        val candidates = transitions.filter { t ->
            t.fromState == currentState
        }

        for (transition in candidates) {
            val condition = transition.condition
            val fires = when (condition.conditionType) {

                ConditionType.EVENT -> {
                    val ec = condition as EventCondition
                    if (eventProcessor.hasEvent(ec.eventDomain, ec.eventValue)) {
                        if (!eventConsumptionOnFire) {
                            // Consume immediately on condition read (legacy behaviour)
                            eventProcessor.consumeEvent(ec.eventDomain, ec.eventValue)
                        }
                        // When eventConsumptionOnFire = true, Engine.applyTransition handles consumption
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

            if (fires) yield(transition)
        }
    }

    /**
     * Evaluates a [ValueCondition] by resolving the boolean variable path against the context
     * model element. The path must resolve to a Boolean value.
     *
     * Special case: if the variable is the literal "true" or "false", it is treated as a
     * constant — this is how unconditional transitions (no CONDITION block in the DSL) are
     * represented after parsing.
     */
    private fun evaluateValueCondition(vc: ValueCondition): Boolean {
        // Handle literal true/false for unconditional transitions
        val literal = vc.boolVariable.trim().lowercase()
        if (literal == "true") return true
        if (literal == "false") return false

        val context = modelQueryProcessor.getDataObjectById(stateMachineAttachedToModelElement)
        val resolved = modelQueryProcessor.resolvePathFromObject(context, vc.boolVariable)
        return when (resolved) {
            is Boolean -> resolved
            is String  -> resolved.lowercase() == "true"
            else       -> false
        }
    }
}