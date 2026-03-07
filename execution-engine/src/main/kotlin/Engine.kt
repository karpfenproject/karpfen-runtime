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
package io.karpfen

import instance.Model
import io.karpfen.io.karpfen.exec.*
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import meta.Metamodel
import states.StateMachine
import states.Transition
import java.util.UUID
import kotlin.concurrent.thread

class Engine(
    val metamodel: Metamodel,
    val model: Model,
    val statemachineMap: Map<String, StateMachine>,
    val tickDelayMS: Int,
    /** Shared bus for all engines within one environment. Created externally so engines can share it. */
    val eventBus: EventBus = EventBus(),
    /** Stable unique id for this engine instance; used to track event processing. */
    val engineId: String = UUID.randomUUID().toString(),
    /** Optional trace logger for structured engine execution tracing. */
    val traceLogger: EngineTraceLogger? = null
) {

    private val dataObservationListeners: MutableMap<String, MutableList<DataObservationListener>> = mutableMapOf()
    private var messageOutChannel: Channel? = null

    @Volatile
    private var isRunning: Boolean = false
    private var executionThread: Thread? = null

    /** Per-engine event processor that wraps the shared bus with this engine's identity. */
    val eventProcessor = EventProcessor(engineId, eventBus)

    /** Model query processor – access and update model data; also hosts change publishers. */
    val modelQueryProcessor = ModelQueryProcessor(metamodel, model)

    /**
     * Accepts an event from an external source (e.g., WebSocket) and publishes it to the bus.
     */
    fun receiveExternalEvent(event: Event) {
        eventProcessor.publishExternalEvent(event)
    }

    fun setMessageOutChannel(channel: Channel) {
        this.messageOutChannel = channel
    }

    fun registerDataObservationListener(objectId: String, listener: DataObservationListener) {
        val listeners = dataObservationListeners.getOrPut(objectId) { mutableListOf() }
        listeners.add(listener)

        modelQueryProcessor.addChangePublisher(ModelChangePublisher { changedId, _ ->
            if (changedId == objectId) {
                listener.onChange(objectId, changedId)
            }
        })
    }

    /**
     * Holds per-state-machine execution context.
     */
    private data class SMContext(
        val modelElementId: String,
        val stateMachine: StateMachine,
        val smQueryHelper: StateMachineQueryHelper,
        val transitionProcessor: TransitionProcessor,
        val actionProcessor: ActionProcessor,
        var stateStack: List<String>,
        var notEnteredSubstack: MutableList<String>,
        var lastFiredTransition: Transition? = null
    )

    fun start() {
        if (isRunning) return
        isRunning = true

        executionThread = thread(name = "Engine-$engineId", start = true) {
            try {
                runEngine()
            } catch (e: InterruptedException) {
                println("[Engine $engineId] Interrupted")
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                println("[Engine $engineId] Error: ${e.message}")
                e.printStackTrace()
            } finally {
                isRunning = false
                println("[Engine $engineId] Stopped")
            }
        }
    }

    private fun runEngine() {
        // Initialize execution contexts for each attached state machine
        val contexts = statemachineMap.map { (modelElementId, stateMachine) ->
            val smQueryHelper = StateMachineQueryHelper(stateMachine)
            val macroProcessor = MacroProcessor(
                metamodel, model, stateMachine.macros, modelElementId, modelQueryProcessor
            )
            val transitionProcessor = TransitionProcessor(
                stateMachine, metamodel, model, modelElementId,
                macroProcessor, smQueryHelper, modelQueryProcessor, eventProcessor
            )
            val actionProcessor = ActionProcessor(
                macroProcessor, modelQueryProcessor, eventProcessor, modelElementId
            )

            val initialStack = smQueryHelper.getInitialStateStack()
            if (initialStack.isEmpty()) {
                throw IllegalStateException(
                    "State machine attached to '$modelElementId' has no initial state"
                )
            }

            traceLogger?.log(
                modelElementId,
                EngineTraceLogger.TraceEventType.INITIAL_STATE,
                "Initial state stack: ${initialStack.joinToString(" > ")}",
                mapOf("stack" to initialStack.joinToString(","))
            )

            SMContext(
                modelElementId = modelElementId,
                stateMachine = stateMachine,
                smQueryHelper = smQueryHelper,
                transitionProcessor = transitionProcessor,
                actionProcessor = actionProcessor,
                stateStack = initialStack,
                // All states in the initial stack need their ENTRY blocks executed
                notEnteredSubstack = initialStack.toMutableList()
            )
        }

        traceLogger?.log(
            "*",
            EngineTraceLogger.TraceEventType.ENGINE_START,
            "Engine started with ${contexts.size} state machine(s), tickDelay=${tickDelayMS}ms"
        )
        println("[Engine $engineId] Started with ${contexts.size} state machine(s)")

        var tickCount = 0L
        while (isRunning) {
            tickCount++
            // Purge expired events once per tick
            eventProcessor.purgeExpired()

            for (ctx in contexts) {
                traceLogger?.log(
                    ctx.modelElementId,
                    EngineTraceLogger.TraceEventType.TICK_START,
                    "Tick #$tickCount",
                    mapOf("currentState" to ctx.stateStack.last(), "stack" to ctx.stateStack.joinToString(","))
                )
                tickStateMachine(ctx)
                traceLogger?.log(
                    ctx.modelElementId,
                    EngineTraceLogger.TraceEventType.TICK_END,
                    "Tick #$tickCount completed",
                    mapOf("currentState" to ctx.stateStack.last(), "stack" to ctx.stateStack.joinToString(","))
                )
            }

            Thread.sleep(tickDelayMS.toLong())
        }

        traceLogger?.log(
            "*",
            EngineTraceLogger.TraceEventType.ENGINE_STOP,
            "Engine stopped after $tickCount ticks"
        )
    }

    /**
     * Executes a single tick for one state machine context.
     *
     * The tick proceeds in phases:
     * 1. ENTRY phase: Execute onEntry blocks for states in the notEnteredSubstack (top-down).
     *    After each entry block, check for transitions. If one fires, compute the new stack
     *    and restart the ENTRY phase on the next tick.
     * 2. DO phase: If no transition fired during ENTRY, execute the onDo block of the
     *    innermost (current) state, then check for transitions.
     */
    private fun tickStateMachine(ctx: SMContext) {
        val smQueryHelper = ctx.smQueryHelper

        // --- ENTRY phase ---
        while (ctx.notEnteredSubstack.isNotEmpty()) {
            val stateName = ctx.notEnteredSubstack.removeFirst()
            val stateObj = smQueryHelper.findStateByName(stateName) ?: continue

            // Execute the onEntry block
            if (stateObj.onEntry.actions.isNotEmpty()) {
                traceLogger?.log(
                    ctx.modelElementId,
                    EngineTraceLogger.TraceEventType.STATE_ENTRY_EXEC,
                    "Executing onEntry for state '$stateName' (${stateObj.onEntry.actions.size} actions)",
                    mapOf("state" to stateName)
                )
                try {
                    ctx.actionProcessor.executeBlock(stateObj.onEntry)
                } catch (e: Exception) {
                    traceLogger?.log(
                        ctx.modelElementId,
                        EngineTraceLogger.TraceEventType.ACTION_ERROR,
                        "Error in onEntry for state '$stateName': ${e.message}",
                        mapOf("state" to stateName, "error" to (e.message ?: "unknown"))
                    )
                    System.err.println("[Engine] Error executing onEntry for state '$stateName': ${e.message}")
                }
            } else {
                traceLogger?.log(
                    ctx.modelElementId,
                    EngineTraceLogger.TraceEventType.STATE_ENTRY_SKIP,
                    "onEntry for state '$stateName' is empty — skipped",
                    mapOf("state" to stateName)
                )
            }

            // Check for a transition after entry — but only if entry actually executed actions.
            // If ENTRY was empty, we should proceed to DO before checking transitions.
            if (stateObj.onEntry.actions.isNotEmpty()) {
                val transition = findFireableTransition(ctx)
                if (transition != null) {
                    applyTransition(transition, ctx, smQueryHelper)
                    return // Exit the tick; the new ENTRY phase will run on the next tick
                }
            }
        }

        // --- DO phase (only the innermost state) ---
        val currentStateName = ctx.stateStack.last()
        val currentState = smQueryHelper.findStateByName(currentStateName)
        if (currentState != null && currentState.onDo.actions.isNotEmpty()) {
            traceLogger?.log(
                ctx.modelElementId,
                EngineTraceLogger.TraceEventType.STATE_DO_EXEC,
                "Executing onDo for state '$currentStateName' (${currentState.onDo.actions.size} actions)",
                mapOf("state" to currentStateName)
            )
            try {
                ctx.actionProcessor.executeBlock(currentState.onDo)
            } catch (e: Exception) {
                traceLogger?.log(
                    ctx.modelElementId,
                    EngineTraceLogger.TraceEventType.ACTION_ERROR,
                    "Error in onDo for state '$currentStateName': ${e.message}",
                    mapOf("state" to currentStateName, "error" to (e.message ?: "unknown"))
                )
                System.err.println("[Engine] Error executing onDo for state '$currentStateName': ${e.message}")
            }
        } else {
            traceLogger?.log(
                ctx.modelElementId,
                EngineTraceLogger.TraceEventType.STATE_DO_SKIP,
                "onDo for state '$currentStateName' is empty — skipped",
                mapOf("state" to currentStateName)
            )
        }

        // Check for a transition after DO
        val transition = findFireableTransition(ctx)
        if (transition != null) {
            applyTransition(transition, ctx, smQueryHelper)
        }
    }

    /**
     * Finds the first transition from the current state stack that is both executable
     * (condition evaluates to true) AND allowed to fire (not blocked by NOT LOOPING).
     *
     * Checks states from innermost to outermost. For each state, iterates through ALL
     * executable transitions and skips any that are blocked by the NOT LOOPING rule.
     */
    private fun findFireableTransition(ctx: SMContext): Transition? {
        for (i in ctx.stateStack.indices.reversed()) {
            val stateName = ctx.stateStack[i]
            val candidates = ctx.transitionProcessor.findAllExecutableTransitions(stateName)
            for (transition in candidates) {
                if (shouldFireTransition(transition, ctx)) {
                    return transition
                }
            }
        }
        return null
    }

    /**
     * Determines whether a transition should fire, respecting the NOT-LOOPING rule:
     * If the transition is marked NOT LOOPING and it is the exact same transition that
     * was last fired, skip it. This prevents the engine from repeatedly firing the same
     * transition on consecutive ticks (e.g. drive → drive fast every tick instead of
     * eventually falling through to drive → observe).
     */
    private fun shouldFireTransition(transition: Transition, ctx: SMContext): Boolean {
        if (!transition.allowLoops && ctx.lastFiredTransition == transition) {
            traceLogger?.log(
                ctx.modelElementId,
                EngineTraceLogger.TraceEventType.TRANSITION_SKIPPED_LOOP,
                "Skipped NOT LOOPING transition: ${transition.fromState} -> ${transition.toState}",
                mapOf("from" to transition.fromState, "to" to transition.toState)
            )
            return false
        }
        return true
    }

    /**
     * Applies a transition: computes the new state stack, determines which states need entry,
     * and updates the context.
     */
    private fun applyTransition(
        transition: Transition,
        ctx: SMContext,
        smQueryHelper: StateMachineQueryHelper
    ) {
        val oldStack = ctx.stateStack
        val newStackBase = smQueryHelper.getStateStackForState(transition.toState)

        if (newStackBase.isEmpty()) {
            traceLogger?.log(
                ctx.modelElementId,
                EngineTraceLogger.TraceEventType.ENGINE_ERROR,
                "Cannot find target state '${transition.toState}' — skipping transition",
                mapOf("from" to transition.fromState, "to" to transition.toState)
            )
            System.err.println("[Engine] Cannot find state '${transition.toState}' — skipping transition")
            return
        }

        // Determine which states need their ENTRY executed
        val changedSequence = StateStackHelper.getChangedStackSequence(oldStack, newStackBase)

        ctx.stateStack = newStackBase
        ctx.notEnteredSubstack = changedSequence.toMutableList()
        ctx.lastFiredTransition = transition

        traceLogger?.log(
            ctx.modelElementId,
            EngineTraceLogger.TraceEventType.TRANSITION_FIRED,
            "${transition.fromState} -> ${transition.toState}",
            mapOf(
                "from" to transition.fromState,
                "to" to transition.toState,
                "conditionType" to transition.condition.conditionType.name,
                "allowLoops" to transition.allowLoops.toString(),
                "oldStack" to oldStack.joinToString(","),
                "newStack" to newStackBase.joinToString(","),
                "statesToEnter" to changedSequence.joinToString(",")
            )
        )
        println("[Engine $engineId] Transition: ${transition.fromState} -> ${transition.toState}")
    }

    fun stop() {
        isRunning = false
        executionThread?.let { t ->
            try {
                t.join(5000)
                if (t.isAlive) {
                    t.interrupt()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        traceLogger?.close()
    }
}