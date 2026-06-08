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
import states.JoinTransition
import states.StateMachine
import java.util.UUID
import kotlin.concurrent.thread

class Engine(
    val metamodel: Metamodel,
    val model: Model,
    val statemachineMap: Map<String, StateMachine>,
    val tickDelayMS: Int,
    /** Shared bus for all engines within one environment. Created externally so engines can share it. */
    val eventBus: EventBus = EventBus(), // TTL is configured via application.conf (defaultEventTtlMs); this default is only used in tests that don't supply an explicit bus
    /** Stable unique id for this engine instance; used to track event processing. */
    val engineId: String = UUID.randomUUID().toString(),
    /** Optional trace logger for structured engine execution tracing. */
    val traceLogger: EngineTraceLogger? = null,
    /** When true (default), events are consumed only when a transition fires. When false, consumed on condition read. */
    val eventConsumptionOnFire: Boolean = true,
    /** Metamodel for event payloads; used to parse incoming payloads into objects. Null when unused. */
    val eventMetamodel: Metamodel? = null
) {

    /** Parses incoming event payloads into the runtime object model against [eventMetamodel]. */
    private val eventPayloadParser = EventPayloadParser(eventMetamodel)

    private val dataObservationListeners: MutableMap<String, MutableList<DataObservationListener>> = mutableMapOf()
    private var messageOutChannel: Channel? = null

    /** Macro processors created during engine run, tracked for cleanup. */
    private val macroProcessors: MutableList<MacroProcessor> = mutableListOf()

    @Volatile
    private var isRunning: Boolean = false
    private var executionThread: Thread? = null

    /** Per-engine event processor that wraps the shared bus with this engine's identity. */
    val eventProcessor = EventProcessor(engineId, eventBus)

    /** Model query processor – access and update model data; also hosts change publishers. */
    val modelQueryProcessor = ModelQueryProcessor(metamodel, model)

    /** Runs the per-branch mechanics (ENTRY/DO + a branch's own transitions). Shared across branches. */
    private val branchRunner = BranchRunner(traceLogger, eventConsumptionOnFire)

    /**
     * Accepts an event from an external source (e.g., WebSocket), parses its payload into the runtime
     * object model, and publishes it to the bus.
     */
    fun receiveExternalEvent(event: Event) {
        eventPayloadParser.parseInto(event)
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
            macroProcessors.add(macroProcessor)
            val transitionProcessor = TransitionProcessor(
                stateMachine, metamodel, model, modelElementId,
                macroProcessor, smQueryHelper, modelQueryProcessor
            )
            // The action processor only publishes internal events, so its identity is not used for
            // consumption; consumption is tracked per branch via each branch's own event processor.
            val branchIdBase = "$engineId-$modelElementId"
            val actionsEventProcessor = EventProcessor("$branchIdBase-actions", eventBus)
            val actionProcessor = ActionProcessor(
                macroProcessor, modelQueryProcessor, actionsEventProcessor, modelElementId
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

            val ctx = SMContext(
                modelElementId = modelElementId,
                stateMachine = stateMachine,
                smQueryHelper = smQueryHelper,
                transitionProcessor = transitionProcessor,
                actionProcessor = actionProcessor,
                eventBus = eventBus,
                branchIdBase = branchIdBase,
                branches = mutableListOf()
            )
            // The context starts simple: a single branch whose whole initial stack needs entering.
            ctx.branches = mutableListOf(
                ctx.newBranch(initialStack, initialStack.toMutableList(), null, emptySet())
            )
            ctx
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
            val tickStart = System.currentTimeMillis()

            // Purge expired events once per tick
            eventProcessor.purgeExpired()
            //println(eventProcessor.eventBus.getUnprocessedEvents("public", engineId).size)

            for (ctx in contexts) {
                traceLogger?.log(
                    ctx.modelElementId,
                    EngineTraceLogger.TraceEventType.TICK_START,
                    "Tick #$tickCount",
                    tickDetails(ctx)
                )
                tickStateMachine(ctx)
                traceLogger?.log(
                    ctx.modelElementId,
                    EngineTraceLogger.TraceEventType.TICK_END,
                    "Tick #$tickCount completed",
                    tickDetails(ctx)
                )
            }

            val elapsed = System.currentTimeMillis() - tickStart
            val remaining = tickDelayMS.toLong() - elapsed
            if (remaining > 0) Thread.sleep(remaining)
        }

        traceLogger?.log(
            "*",
            EngineTraceLogger.TraceEventType.ENGINE_STOP,
            "Engine stopped after $tickCount ticks"
        )
    }

    /**
     * Trace details for a tick. "stack" lists the active states across all branches (equal to the
     * single stack in simple mode); "currentStates" lists each branch's innermost state.
     */
    private fun tickDetails(ctx: SMContext): Map<String, String> = mapOf(
        "stack" to ctx.branches.flatMap { it.stateStack }.joinToString(","),
        "currentStates" to ctx.branches.joinToString(",") { it.currentState() },
        "parallel" to ctx.isParallel().toString()
    )

    /**
     * Executes a single tick for one state machine context.
     *
     * The top-level loop stays small: in a parallel context a JOIN is checked first (it has priority
     * and can collapse the context back to simple); then every branch is ticked in order by the
     * [BranchRunner]. Only a simple context may take a SPLIT, which the branch reports back so this
     * method can carry out the structural change. A simple context is just the same loop with one
     * branch.
     */
    private fun tickStateMachine(ctx: SMContext) {
        if (ctx.isParallel()) {
            val join = selectFireableJoin(ctx)
            if (join != null) {
                applyJoin(ctx, join)
                return
            }
        }

        val allowSplit = !ctx.isParallel()
        for (branch in ctx.branches.toList()) {
            val result = branchRunner.tick(ctx, branch, allowSplit)
            if (result is BranchTickResult.SplitRequested) {
                applySplit(ctx, branch, result.split, result.matchedEvent)
            }
        }
    }

    /**
     * Finds a join whose source states are matched by the current branches and whose condition holds.
     *
     * A join fires only when the set of branches exactly matches its source states: there must be as
     * many branches as source states, and each source state must be active (anywhere in the stack) in
     * a distinct branch.
     */
    private fun selectFireableJoin(ctx: SMContext): Fireable? {
        for (join in ctx.stateMachine.joinTransitions()) {
            if (!branchesMatchJoin(join.fromStates, ctx.branches)) continue
            val fireable = ctx.transitionProcessor.evaluateJoin(join, ctx.joinEventProcessor)
            if (fireable != null) return fireable
        }
        return null
    }

    /**
     * True if there is a perfect matching of [fromStates] to distinct [branches] such that each source
     * state is active somewhere in its assigned branch's stack (and every branch is used).
     */
    private fun branchesMatchJoin(fromStates: List<String>, branches: List<Branch>): Boolean {
        if (fromStates.size != branches.size) return false
        val used = BooleanArray(branches.size)
        fun match(i: Int): Boolean {
            if (i == fromStates.size) return true
            for (b in branches.indices) {
                if (!used[b] && branches[b].stateStack.contains(fromStates[i])) {
                    used[b] = true
                    if (match(i + 1)) return true
                    used[b] = false
                }
            }
            return false
        }
        return match(0)
    }

    /**
     * Fans [parent] out into one new branch per split target, turning the context parallel. Each child
     * inherits the parent's event lineage, and the matched event (if any) is scoped to each target so
     * every region can read it.
     */
    private fun applySplit(ctx: SMContext, parent: Branch, split: states.SplitTransition, matchedEvent: Event?) {
        if (eventConsumptionOnFire) matchedEvent?.let { parent.eventProcessor.consume(it) }

        val inherited = parent.eventProcessor.lineage()
        val newBranches = split.toStates.mapNotNull { target ->
            val targetStack = ctx.smQueryHelper.getStateStackForState(target)
            if (targetStack.isEmpty()) {
                System.err.println("[Engine] Split target '$target' not found — skipping it")
                return@mapNotNull null
            }
            val entry = StateStackHelper.computeEntrySequence(parent.stateStack, targetStack, target)
            ctx.newBranch(targetStack, entry.toMutableList(), split, inherited).also { branch ->
                matchedEvent?.let { branch.scopedEventByState[target] = it }
            }
        }.toMutableList()

        if (newBranches.isEmpty()) return // nothing valid to split into; leave the context unchanged
        ctx.branches = newBranches

        traceLogger?.log(
            ctx.modelElementId,
            EngineTraceLogger.TraceEventType.SPLIT_FIRED,
            "${split.fromState} -> ${split.toStates.joinToString(",")}",
            mapOf("from" to split.fromState, "to" to split.toStates.joinToString(","))
        )
    }

    /**
     * Collapses all parallel branches into a single new branch entering the join target, turning the
     * context simple again. The new branch inherits the union of the joined branches' lineages so it
     * does not re-handle events the parallel phase already handled.
     */
    private fun applyJoin(ctx: SMContext, fireable: Fireable) {
        val join = fireable.transition as JoinTransition
        val targetStack = ctx.smQueryHelper.getStateStackForState(join.toState)
        if (targetStack.isEmpty()) {
            System.err.println("[Engine] Join target '${join.toState}' not found — skipping join")
            return
        }

        val inherited = ctx.branches.flatMap { it.eventProcessor.lineage() }.toSet()
        val entry = StateStackHelper.computeEntrySequence(emptyList(), targetStack, join.toState)
        val newBranch = ctx.newBranch(targetStack, entry.toMutableList(), join, inherited)
        if (eventConsumptionOnFire) fireable.matchedEvent?.let { newBranch.eventProcessor.consume(it) }
        ctx.branches = mutableListOf(newBranch)

        traceLogger?.log(
            ctx.modelElementId,
            EngineTraceLogger.TraceEventType.JOIN_FIRED,
            "${join.fromStates.joinToString(",")} -> ${join.toState}",
            mapOf("from" to join.fromStates.joinToString(","), "to" to join.toState)
        )
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
        // Close all persistent Python sessions
        val processors = ArrayList(macroProcessors)
        macroProcessors.clear()
        for (mp in processors) {
            try {
                mp.close()
            } catch (_: Exception) { }
        }
        traceLogger?.close()
    }
}