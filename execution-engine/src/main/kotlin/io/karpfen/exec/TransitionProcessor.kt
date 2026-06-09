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
import states.SplitTransition
import states.TransitionLike
import states.conditions.CompositeCondition
import states.conditions.Condition
import states.conditions.EvalCondition
import states.conditions.EventCondition
import states.conditions.ValueCondition

/**
 * A transition (of any kind) that is ready to fire, together with the event that enabled it (if any).
 */
data class Fireable(val transition: TransitionLike, val matchedEvent: Event?)

/** Result of evaluating a condition: it holds, carrying the matched event (null when no EVENT clause). */
private data class MatchResult(val event: Event?)

/** Flattens a condition into its leaf clauses; a [CompositeCondition] exposes its parts in order. */
fun Condition.leafClauses(): List<Condition> =
    if (this is CompositeCondition) clauses else listOf(this)

/**
 * Evaluates transition conditions. It is intentionally stateless with respect to *which* branch is
 * being evaluated: the relevant [EventProcessor] and ambient (scoped) event are passed in per call, so
 * one processor instance serves every branch of a parallel context.
 */
class TransitionProcessor(
    val stateMachine: StateMachine,
    val metamodel: Metamodel,
    val model: Model,
    val stateMachineAttachedToModelElement: String,
    val macroProcessor: MacroProcessor,
    val stateMachineQueryHelper: StateMachineQueryHelper,
    val modelQueryProcessor: ModelQueryProcessor
) {

    /**
     * Yields the normal (and, when [includeSplits] is true, split) transitions that can fire from
     * [currentState], in definition order. No event is consumed here — consumption happens when the
     * engine actually fires the transition.
     *
     * @param ambientEvent   The scoped event payload, for guard clauses on transitions without an EVENT clause.
     * @param eventProcessor The branch's event processor, used to find candidate events.
     * @param includeSplits  Whether split transitions are eligible (false while already parallel).
     */
    fun findFireable(
        currentState: String,
        ambientEvent: DataObject?,
        eventProcessor: EventProcessor,
        includeSplits: Boolean
    ): Sequence<Fireable> = sequence {
        for (transition in stateMachine.outgoingFrom(currentState)) {
            if (transition is SplitTransition && !includeSplits) continue
            val match = evaluateCondition(transition.condition, ambientEvent, eventProcessor)
            if (match != null) yield(Fireable(transition, match.event))
        }
    }

    /**
     * Evaluates a join's condition (the source-state matching is done by the engine). Returns a
     * [Fireable] when the condition holds, or null otherwise.
     */
    fun evaluateJoin(join: states.JoinTransition, eventProcessor: EventProcessor): Fireable? {
        val match = evaluateCondition(join.condition, null, eventProcessor) ?: return null
        return Fireable(join, match.event)
    }

    /**
     * Evaluates a condition (a list of clauses, all of which must hold). When an EVENT clause is
     * present it must be first; the bus is scanned for candidate events (oldest-first) and the first
     * one whose guard clauses all pass is returned as the match.
     */
    private fun evaluateCondition(
        condition: Condition,
        ambientEvent: DataObject?,
        eventProcessor: EventProcessor
    ): MatchResult? {
        val clauses = condition.leafClauses()
        val eventClause = clauses.firstOrNull { it is EventCondition } as? EventCondition
        val guardClauses = clauses.filter { it !is EventCondition }

        if (eventClause == null) {
            return if (guardClauses.all { evaluateGuard(it, ambientEvent) }) MatchResult(null) else null
        }

        for (event in eventProcessor.getEvents(eventClause.eventDomain, eventClause.eventValue)) {
            val eventObj = event.payloadObject
            if (guardClauses.all { evaluateGuard(it, eventObj) }) {
                return MatchResult(event)
            }
        }
        return null
    }

    /** Evaluates a single guard clause (EVAL or VALUE) with [eventObj] bound as the event in scope. */
    private fun evaluateGuard(clause: Condition, eventObj: DataObject?): Boolean {
        return when (clause) {
            is EvalCondition -> try {
                macroProcessor.executeInlineMacro(clause.code, "boolean", ModelQueryProcessor.eventScope(eventObj)) == true
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
            else -> false
        }
    }

    /**
     * Evaluates a [ValueCondition] by resolving the boolean variable path against the context object
     * (or the event in scope, for `event->...` paths). The literals "true"/"false" are constants.
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
