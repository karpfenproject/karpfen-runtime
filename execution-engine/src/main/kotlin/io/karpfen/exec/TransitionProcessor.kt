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

import instance.DataObject
import instance.Model
import io.karpfen.io.karpfen.messages.Event
import meta.Metamodel
import states.StateMachine
import states.Transition
import states.conditions.CompositeCondition
import states.conditions.Condition
import states.conditions.EvalCondition
import states.conditions.EventCondition
import states.conditions.ValueCondition

/**
 * A transition that is ready to fire, together with the event (if any) that enabled it.
 *
 * For an EVENT-gated transition, [matchedEvent] is the specific event whose guards all passed; the
 * engine consumes exactly that event when the transition fires and keeps it as the state's scoped
 * event. For a plain transition, [matchedEvent] is null.
 */
data class FireableTransition(val transition: Transition, val matchedEvent: Event?)

/** Flattens a condition into its leaf clauses; a [CompositeCondition] exposes its parts in order. */
fun Condition.leafClauses(): List<Condition> =
    if (this is CompositeCondition) clauses else listOf(this)

class TransitionProcessor(
    val stateMachine: StateMachine,
    val metamodel: Metamodel,
    val model: Model,
    val stateMachineAttachedToModelElement: String,
    val macroProcessor: MacroProcessor,
    val stateMachineQueryHelper: StateMachineQueryHelper,
    val modelQueryProcessor: ModelQueryProcessor,
    val eventProcessor: EventProcessor,
    /** Retained for configuration compatibility; consumption now always happens when a transition fires. */
    val eventConsumptionOnFire: Boolean = true
) {

    val transitions = stateMachine.transitions

    /**
     * Finds the first transition that can fire from [currentState].
     *
     * @param currentState The name of the active state.
     * @param ambientEvent The payload of the event currently in scope for this state, used to resolve
     *                     `$(event->...)` in guard clauses of transitions that do not have their own
     *                     EVENT clause. Null when no event is in scope.
     */
    fun findFirstExecutableTransition(currentState: String, ambientEvent: DataObject? = null): FireableTransition? {
        return findAllExecutableTransitions(currentState, ambientEvent).firstOrNull()
    }

    /**
     * Finds all transitions whose conditions hold for [currentState], in definition order.
     *
     * A condition is an ordered list of clauses that must all hold. When a transition has an EVENT
     * clause, the bus is scanned for candidate events of that name (oldest-first); the first event
     * whose guard clauses all pass enables the transition and is reported as the matched event. No
     * event is consumed here — consumption happens when the engine actually fires the transition.
     *
     * @param currentState The name of the active state.
     * @param ambientEvent The event payload in scope, for guard clauses on non-EVENT transitions.
     * @return A sequence of fireable transitions in definition order.
     */
    fun findAllExecutableTransitions(currentState: String, ambientEvent: DataObject? = null): Sequence<FireableTransition> = sequence {
        val candidates = transitions.filter { it.fromState == currentState }
        for (transition in candidates) {
            val fireable = evaluateTransition(transition, ambientEvent)
            if (fireable != null) yield(fireable)
        }
    }

    /**
     * Evaluates one transition's condition. Returns a [FireableTransition] (with the matched event, if
     * any) when it can fire, or null otherwise.
     */
    private fun evaluateTransition(transition: Transition, ambientEvent: DataObject?): FireableTransition? {
        val clauses = transition.condition.leafClauses()
        val eventClause = clauses.firstOrNull { it is EventCondition } as? EventCondition
        val guardClauses = clauses.filter { it !is EventCondition }

        if (eventClause == null) {
            // No EVENT clause: evaluate the guards against the ambient (scoped) event, if any.
            return if (guardClauses.all { evaluateGuard(it, ambientEvent) }) {
                FireableTransition(transition, null)
            } else {
                null
            }
        }

        // EVENT-gated: scan candidate events oldest-first and take the first whose guards all pass.
        // Each candidate is bound as the event in scope so guards can read $(event->...).
        for (event in eventProcessor.getEvents(eventClause.eventDomain, eventClause.eventValue)) {
            val eventObj = event.payloadObject
            if (guardClauses.all { evaluateGuard(it, eventObj) }) {
                return FireableTransition(transition, event)
            }
        }
        return null
    }

    /**
     * Evaluates a single guard clause (EVAL or VALUE) with [eventObj] bound as the event in scope.
     * Any failure is treated as "does not hold".
     */
    private fun evaluateGuard(clause: Condition, eventObj: DataObject?): Boolean {
        return when (clause) {
            is EvalCondition -> try {
                macroProcessor.executeInlineMacro(clause.code, "boolean", eventObj) == true
            } catch (e: Exception) {
                System.err.println("[TransitionProcessor] EVAL condition failed: ${e.message}")
                false
            }
            is ValueCondition -> try {
                evaluateValueCondition(clause, eventObj)
            } catch (e: Exception) {
                System.err.println("[TransitionProcessor] VALUE condition failed: ${e.message}")
                false
            }
            // A second EVENT clause is not allowed in a condition; ignore it defensively.
            else -> false
        }
    }

    /**
     * Evaluates a [ValueCondition] by resolving the boolean variable path against the context object
     * (or the event in scope, for `event->...` paths). The path must resolve to a Boolean value.
     *
     * Special case: the literals "true" / "false" are treated as constants — this is how unconditional
     * transitions (no CONDITION block) are represented after parsing.
     */
    private fun evaluateValueCondition(vc: ValueCondition, eventObj: DataObject?): Boolean {
        val literal = vc.boolVariable.trim().lowercase()
        if (literal == "true") return true
        if (literal == "false") return false

        val context = modelQueryProcessor.getDataObjectById(stateMachineAttachedToModelElement)
        val resolved = modelQueryProcessor.resolvePathWithEvent(context, eventObj, vc.boolVariable)
        return when (resolved) {
            is Boolean -> resolved
            is String  -> resolved.lowercase() == "true"
            else       -> false
        }
    }
}
