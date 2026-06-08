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

import io.karpfen.EngineTraceLogger
import io.karpfen.io.karpfen.messages.Event
import states.SplitTransition
import states.Transition
import states.TransitionLike
import states.conditions.EventCondition

/**
 * Runs one tick of a single [Branch]: its ENTRY phase, its DO phase, and its transition check.
 *
 * It is deliberately unaware of whether the context is simple or parallel — it is handed one branch
 * and told whether splits are currently allowed. A normal transition is applied in place; a split is
 * reported back to the caller (see [BranchTickResult]). In a simple context the engine simply runs
 * this over the single branch, so the non-parallel path is exactly the same code as the parallel one.
 */
class BranchRunner(
    private val traceLogger: EngineTraceLogger?,
    private val eventConsumptionOnFire: Boolean
) {

    fun tick(ctx: SMContext, branch: Branch, allowSplit: Boolean): BranchTickResult {
        val smQueryHelper = ctx.smQueryHelper

        // --- ENTRY phase ---
        while (branch.notEnteredSubstack.isNotEmpty()) {
            val stateName = branch.notEnteredSubstack.removeFirst()
            val stateObj = smQueryHelper.findStateByName(stateName) ?: continue

            if (stateObj.onEntry.isNotEmpty()) {
                trace(ctx, branch, EngineTraceLogger.TraceEventType.STATE_ENTRY_EXEC,
                    "Executing onEntry for state '$stateName'", mapOf("state" to stateName))
                try {
                    ctx.actionProcessor.executeBlock(stateObj.onEntry, branch.currentScopedEvent()?.payloadObject)
                } catch (e: Exception) {
                    trace(ctx, branch, EngineTraceLogger.TraceEventType.ACTION_ERROR,
                        "Error in onEntry for state '$stateName': ${e.message}", mapOf("state" to stateName))
                    System.err.println("[BranchRunner] Error executing onEntry for state '$stateName': ${e.message}")
                }

                // Only check for transitions if the ENTRY actually ran actions (matches simple-mode rule).
                val fireable = findFireable(ctx, branch, allowSplit)
                if (fireable != null) return applyOrRequest(ctx, branch, fireable)
            } else {
                trace(ctx, branch, EngineTraceLogger.TraceEventType.STATE_ENTRY_SKIP,
                    "onEntry for state '$stateName' is empty — skipped", mapOf("state" to stateName))
            }
        }

        // --- DO phase (innermost state only) ---
        val currentStateName = branch.currentState()
        val currentState = smQueryHelper.findStateByName(currentStateName)
        if (currentState != null && currentState.onDo.isNotEmpty()) {
            trace(ctx, branch, EngineTraceLogger.TraceEventType.STATE_DO_EXEC,
                "Executing onDo for state '$currentStateName'", mapOf("state" to currentStateName))
            try {
                ctx.actionProcessor.executeBlock(currentState.onDo, branch.currentScopedEvent()?.payloadObject)
            } catch (e: Exception) {
                trace(ctx, branch, EngineTraceLogger.TraceEventType.ACTION_ERROR,
                    "Error in onDo for state '$currentStateName': ${e.message}", mapOf("state" to currentStateName))
                System.err.println("[BranchRunner] Error executing onDo for state '$currentStateName': ${e.message}")
            }
        } else {
            trace(ctx, branch, EngineTraceLogger.TraceEventType.STATE_DO_SKIP,
                "onDo for state '$currentStateName' is empty — skipped", mapOf("state" to currentStateName))
        }

        val fireable = findFireable(ctx, branch, allowSplit)
        if (fireable != null) return applyOrRequest(ctx, branch, fireable)

        // Nothing fired: burn events that were offered to this branch but consumed by nothing.
        burnDispatchedEvents(ctx, branch)
        return BranchTickResult.Stable
    }

    private fun applyOrRequest(ctx: SMContext, branch: Branch, fireable: Fireable): BranchTickResult {
        return when (val transition = fireable.transition) {
            is SplitTransition -> BranchTickResult.SplitRequested(transition, fireable.matchedEvent)
            is Transition -> { applyNormal(ctx, branch, transition, fireable.matchedEvent); BranchTickResult.Stable }
            else -> BranchTickResult.Stable // joins are never produced by branch-level selection
        }
    }

    /**
     * Finds the first fireable normal/split transition for [branch], scanning its stack innermost to
     * outermost and respecting NOT LOOPING. Splits are only considered when [allowSplit] is true.
     */
    private fun findFireable(ctx: SMContext, branch: Branch, allowSplit: Boolean): Fireable? {
        val ambient = branch.currentScopedEvent()?.payloadObject
        for (i in branch.stateStack.indices.reversed()) {
            val stateName = branch.stateStack[i]
            for (fireable in ctx.transitionProcessor.findFireable(stateName, ambient, branch.eventProcessor, allowSplit)) {
                if (shouldFire(ctx, branch, fireable.transition)) return fireable
            }
        }
        return null
    }

    private fun shouldFire(ctx: SMContext, branch: Branch, transition: TransitionLike): Boolean {
        if (!transition.allowLoops && branch.lastFiredTransition == transition) {
            trace(ctx, branch, EngineTraceLogger.TraceEventType.TRANSITION_SKIPPED_LOOP,
                "Skipped NOT LOOPING transition", emptyMap())
            return false
        }
        return true
    }

    /** Applies a normal 1-to-1 transition by mutating [branch] in place. */
    private fun applyNormal(ctx: SMContext, branch: Branch, transition: Transition, matchedEvent: Event?) {
        val oldStack = branch.stateStack
        val newStackBase = ctx.smQueryHelper.getStateStackForState(transition.toState)
        if (newStackBase.isEmpty()) {
            System.err.println("[BranchRunner] Cannot find state '${transition.toState}' — skipping transition")
            return
        }

        if (eventConsumptionOnFire) matchedEvent?.let { branch.eventProcessor.consume(it) }

        val changedSequence = StateStackHelper.computeEntrySequence(oldStack, newStackBase, transition.toState)
        branch.stateStack = newStackBase
        branch.notEnteredSubstack = changedSequence.toMutableList()
        branch.lastFiredTransition = transition

        branch.scopedEventByState.keys.retainAll(newStackBase.toSet())
        matchedEvent?.let { branch.scopedEventByState[transition.toState] = it }

        trace(ctx, branch, EngineTraceLogger.TraceEventType.TRANSITION_FIRED,
            "${transition.fromState} -> ${transition.toState}",
            mapOf("from" to transition.fromState, "to" to transition.toState))
    }

    /** Consumes (for this branch's lineage) every event that matched a trigger but fired nothing. */
    private fun burnDispatchedEvents(ctx: SMContext, branch: Branch) {
        val ep = branch.eventProcessor
        val burned = HashSet<Event>()
        for (stateName in branch.stateStack) {
            for (transition in ctx.stateMachine.outgoingFrom(stateName)) {
                for (clause in transition.condition.leafClauses()) {
                    if (clause is EventCondition) {
                        for (event in ep.getEvents(clause.eventDomain, clause.eventValue)) {
                            if (burned.add(event)) ep.consume(event)
                        }
                    }
                }
            }
        }
    }

    private fun trace(
        ctx: SMContext,
        branch: Branch,
        type: EngineTraceLogger.TraceEventType,
        message: String,
        details: Map<String, String>
    ) {
        if (traceLogger == null) return
        val withBranch = details + ("branch" to branch.eventProcessor.engineId) + ("stack" to branch.stateStack.joinToString(","))
        traceLogger.log(ctx.modelElementId, type, message, withBranch)
    }
}
