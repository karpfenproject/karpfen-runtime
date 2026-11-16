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

import io.karpfen.dsl.instance.Model
import io.karpfen.io.karpfen.exec.*
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.language.HistoryFeature
import io.karpfen.io.karpfen.features.language.TickLimitFeature
import io.karpfen.io.karpfen.features.runtime.BreakpointFeature
import io.karpfen.io.karpfen.features.runtime.TickByTickFeature
import io.karpfen.io.karpfen.features.runtime.TrueParallelFeature
import io.karpfen.io.karpfen.features.runtime.event.EventInjectionFeature
import io.karpfen.io.karpfen.features.runtime.event.EventRecordingFeature
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.metrics.RunMetrics
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.JoinTransition
import io.karpfen.dsl.states.StateMachine
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Executes the state machine instances of the objects of one model, on an engine thread of its own.
 *
 * The instances, which ones exist and in which order they step, belong to a [StateMachineLifecycle]. At the first
 * start it builds the attached state machines of [statemachineMap] and the instances for `behaves after` over the
 * model; during the run, actions and changes of the model ask it for new, exchanged and ended instances, and it carries
 * the requests out at the end of each tick. The engine walks the instances it hands out and skips an instance that
 * ended during the tick.
 *
 * [stop] freezes the run and a later [start] continues it with the next tick: the instances with their stacks,
 * branches and events, the tick number, the state of the features and the time to live left to the events on the bus
 * all stay as they were, see [stop]. [close] ends the engine for good.
 *
 * [start] runs the tick loop. One tick holds the [tickGate] and runs, in this order, the queued engine commands, the
 * purge of expired events, and for each state machine its tick (its `ENTRY` and `DO` blocks, each one notification
 * batch of the [modelQueryProcessor] published when the block ends, and its transitions) followed by its active-stack
 * notification. Between ticks the engine sleeps for the rest of [tickDelayMS] outside the gate. Every model write and
 * every object and active-stack push happens inside a tick, on the engine thread or, while [TrueParallelFeature] is on,
 * on the thread of a branch of a split state machine (see [ParallelBranchStepper]), so [readBetweenTicks] sees the
 * model at rest with every such push of the finished ticks enqueued. Events from outside arrive through
 * [receiveExternalEvent] on the caller's thread outside any tick; their payloads are new objects that never touch the
 * model, and the listeners of the bus learn about them on that thread.
 *
 * @property metamodel the metamodel of [model].
 * @property model the model the state machines read and change.
 * @property statemachineMap the attached state machines by the id of the object each one is attached to, in the order
 *   in which they step.
 * @property tickDelayMS the least time from the start of one tick to the start of the next one, in milliseconds.
 */
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
    val eventMetamodel: Metamodel? = null,
    /**
     * Gate the engine holds for the whole of each tick; [readBetweenTicks] waits on it, so readers on other threads
     * see the model only between ticks.
     */
    private val tickGate: TickGate = TickGate(),
    /**
     * The features of the environment this engine runs in. The engine asks it at fixed points of a tick for the
     * features that are switched on; without any, the engine runs as it does without a feature system.
     */
    val featureManager: FeatureManager = FeatureManager(),
    /**
     * The attachable state machines by their name: the definitions that `behaves after`, `ATTACH` and `EXCHANGE` name.
     * Every `behaves after` of [metamodel] must name one that lists its type; otherwise the engine is not built.
     */
    stateMachineDefinitions: Map<String, StateMachine> = emptyMap(),
    /**
     * The numbers of this engine: its ticks and the calls of code of its instances. The engine reports every tick to
     * it and hands it to the macro processor of every instance it builds; readers on any thread take a
     * [RunMetrics.snapshot].
     */
    val metrics: RunMetrics = RunMetrics(),
) {

    /** Parses incoming event payloads into the runtime object model against [eventMetamodel]. */
    private val eventPayloadParser = EventPayloadParser(eventMetamodel)

    private val dataObservationListeners: MutableMap<String, MutableList<DataObservationListener>> = mutableMapOf()
    private var messageOutChannel: Channel? = null

    @Volatile
    private var isRunning: Boolean = false

    /** True once the first start has built its instances; every later start continues the run. */
    @Volatile
    private var hasStartedBefore: Boolean = false

    /** The number of the latest tick, counted from 1 over the whole life of the engine; a start goes on counting. */
    @Volatile
    var tickCount: Long = 0L
        private set

    /**
     * True while a [stop] holds the engine thread where it waits for the pause of [TickByTickFeature], between two ticks
     * or at a breakpoint; a [start] releases it. Guarded by [holdLock] for waiting and waking.
     */
    @Volatile
    private var frozen: Boolean = false

    /** True once [close] began; the engine does not start again. */
    @Volatile
    private var closed: Boolean = false

    /** The monitor the engine thread waits on in [awaitStart] while it is held by a [stop]. */
    private val holdLock = Object()

    /** Lets one of [start], [stop] and [close] run at a time. */
    private val lifeLock = Object()

    /** True while a thread of the engine waits for the pause of [TickByTickFeature], also while it is held there. */
    @Volatile
    private var parkedInWait: Boolean = false

    /** True while the engine thread waits for the pause of [TickByTickFeature] between two ticks. */
    @Volatile
    private var waitingBetweenTicks: Boolean = false

    /** The engine thread of the latest [start]; volatile so that other threads read [isTicking] safely. */
    @Volatile
    private var executionThread: Thread? = null

    /**
     * True while the engine runs: its thread is alive and no [stop] holds it. The thread is alive from [start] until the
     * tick loop ends, either through [stop] or [close] or because an exception or error escaped it; a [stop] while the
     * engine waits for the pause of [TickByTickFeature] keeps the thread alive and held.
     */
    val isTicking: Boolean
        get() = executionThread?.isAlive == true && !frozen

    /**
     * True when the engine thread of the latest [start] ended on its own, because an exception or error escaped the
     * tick loop while the engine was not being stopped. [start] resets it before the new thread exists, and a thread
     * that ends because of [stop] does not set it.
     */
    @Volatile
    var hasHalted: Boolean = false
        private set

    /**
     * Called whenever a state machine's active state stack changes (initial state, transition,
     * split, join, or a forced resync). Carries (modelElementId, full active stack). The environment
     * uses this to push lightweight "stateChange" updates to the observatory instead of per-tick
     * trace spam. Invoked on the engine thread, also while branches step on threads of their own.
     */
    var onActiveStackChanged: ((String, List<String>) -> Unit)? = null

    /**
     * Called with the status of the engine, the JSON object that the `getStatus` message of the breakpoint feature
     * answers, each time the engine thread starts to wait for the pause of [TickByTickFeature], between two ticks or at
     * a breakpoint, and each time it goes on: before the thread blocks and right after it wakes. Invoked on the engine
     * thread, or on the thread of a branch that halts at a breakpoint while [TrueParallelFeature] is on, one report at a
     * time, and never while [TickByTickFeature] is switched off. The environment uses it to push `engineStatus` to
     * its clients.
     */
    var onEngineStatusChanged: ((String) -> Unit)? = null

    /**
     * Told when a state machine instance appears on an object and when it leaves, on the engine thread inside the tick
     * gate. The environment uses it to push `stateMachineAttached` and `stateMachineDetached` to its clients.
     */
    var stateMachineObserver: StateMachineObserver? = null

    /** Hands each status the pause reports on to [onEngineStatusChanged], whatever it is at that moment. */
    private val reportEngineStatus: (String) -> Unit = { status -> onEngineStatusChanged?.invoke(status) }

    /**
     * Commands to run on the engine thread at the top of a tick, with access to the live [SMContext]s.
     * Lets external threads (e.g. an HTTP request) safely mutate engine state — the contexts are
     * otherwise engine-thread-confined and unlocked. Used by [forceActiveState].
     */
    private val commandQueue = ConcurrentLinkedQueue<(List<SMContext>) -> Unit>()

    /** Per-engine event processor that wraps the shared bus with this engine's identity. */
    val eventProcessor = EventProcessor(engineId, eventBus)

    /** Model query processor – access and update model data; also hosts change publishers. */
    val modelQueryProcessor = ModelQueryProcessor(metamodel, model)

    /** The attached and attachable state machines of this engine. */
    private val definitions = StateMachineDefinitions(statemachineMap, stateMachineDefinitions).also { known ->
        val problems = known.behavesAfterProblems(metamodel)
        require(problems.isEmpty) {
            "Types behave after state machines the engine cannot attach: " +
                (problems.missing + problems.mismatched).joinToString(", ")
        }
    }

    /** The owner of the state machine instances; see [StateMachineLifecycle]. */
    private val lifecycle = StateMachineLifecycle(
        definitions,
        StateMachineInstanceFactory(metamodel, model, modelQueryProcessor, eventBus, engineId, metrics),
        model,
        modelQueryProcessor,
        InstanceReporter()
    ).also(modelQueryProcessor::addStructureListener)

    /**
     * The state machine instances: before the first start the ones a start would build, afterwards the ones of the
     * latest tick, in the order in which they step. Each names its object, its definition (null for an attached state
     * machine) and its state machine. Safe to read from any thread.
     */
    val stateMachineInstances: List<InstanceSpec>
        get() = lifecycle.view

    /** The state machine of the instance on [objectId] in [stateMachineInstances], or null when the object has none. */
    fun stateMachineOf(objectId: String): StateMachine? =
        lifecycle.view.firstOrNull { it.objectId == objectId }?.stateMachine

    /**
     * Finds the stack a branch rests on when it enters a state, going down into container states. Shared by the
     * [branchRunner] and the splits, joins and forced states of the engine.
     */
    private val stateEntry = StateEntry(featureManager)

    /** Runs the per-branch mechanics (ENTRY/DO + a branch's own transitions). Shared across branches. */
    private val branchRunner = BranchRunner(traceLogger, eventConsumptionOnFire, featureManager, stateEntry)

    /**
     * Steps the branches of split state machines on threads of their own while [TrueParallelFeature] is on. Used by
     * the engine thread only, and closed when that thread ends.
     */
    private val parallelBranchStepper = ParallelBranchStepper(branchRunner)

    /**
     * Accepts an event from an external source (e.g., WebSocket), parses its payload into the runtime
     * object model, and publishes it to the bus.
     */
    fun receiveExternalEvent(event: Event) {
        eventPayloadParser.parseInto(event)
        //Event recording
        featureManager.executeIfPresent<EventRecordingFeature> { feature ->
            if (feature.addEventToQueue(event, eventProcessor)) return
        }
        eventProcessor.publishExternalEvent(event)
    }

    /**
     * Forces the state machine attached to [modelElementId] into the leaf state [leaf], dropping any
     * pending events on its domain. The work is queued and executed on the engine thread at the next
     * tick boundary (the contexts are not visible/safe off-thread). A pure SNAP: the target's
     * ENTRY/DO blocks are NOT re-run, so it does not publish further events. Unknown ids/states are
     * logged and ignored (the HTTP layer validates the state name up front for a clean error). A container state is
     * gone down into as by a transition (see [StateEntry]), so the instance rests on a state inside it.
     *
     * The forced state starts a new path: the paths the instance ran end, and its single new branch runs on the next
     * path number of the object. The `INITIAL_STATE` entry names both, as `ends` and `path`.
     */
    fun forceActiveState(modelElementId: String, leaf: String) {
        commandQueue.add { contexts ->
            val ctx = contexts.firstOrNull { it.modelElementId == modelElementId && !it.hasEnded }
            if (ctx == null) {
                System.err.println("[Engine $engineId] forceActiveState: no state machine '$modelElementId'")
                return@add
            }
            val targetStack = ctx.smQueryHelper.getStateStackForState(leaf)
            if (targetStack.isEmpty()) {
                System.err.println("[Engine $engineId] forceActiveState: unknown state '$leaf' for '$modelElementId'")
                return@add
            }
            val newStack = stateEntry.restingStack(ctx, targetStack)
            eventBus.clearDomain(modelElementId)
            // Empty notEnteredSubstack => pure snap, no ENTRY/DO re-run; empty lineage is safe because
            // the domain bucket was just cleared.
            val ended = ctx.pathList()
            ctx.branches = mutableListOf(ctx.newBranch(newStack, mutableListOf(), null, emptySet()))
            traceLogger?.log(
                modelElementId,
                EngineTraceLogger.TraceEventType.INITIAL_STATE,
                "Forced state: ${newStack.joinToString(" > ")}",
                mapOf("ends" to ended, "path" to ctx.pathList(), "stack" to newStack.joinToString(","))
            )
        }
    }

    fun setMessageOutChannel(channel: Channel) {
        this.messageOutChannel = channel
    }

    fun registerDataObservationListener(objectId: String, listener: DataObservationListener) {
        val listeners = dataObservationListeners.getOrPut(objectId) { mutableListOf() }
        listeners.add(listener)

        modelQueryProcessor.addChangePublisher(object : ModelChangePublisher {
            override fun onObjectChanged(changedId: String, jsonValue: String) {
                if (changedId == objectId) {
                    listener.onChange(objectId, changedId)
                }
            }
        })
    }

    /**
     * Starts the engine unless it runs or was closed. The first start builds the instances; every later start continues
     * the frozen run with the next tick: the waiting events get back the time to live they had left at the [stop], and
     * a thread that the stop held where it waited for the pause of [TickByTickFeature] is released and goes on waiting
     * there until a resume, as before the stop. Otherwise a new engine thread runs the tick loop. [hasHalted] is reset
     * before the thread exists; the thread sets it when it ends while the engine was still meant to run.
     */
    fun start() = synchronized(lifeLock) {
        if (closed || isTicking) return
        isRunning = true
        hasHalted = false
        eventBus.thaw()
        if (executionThread?.isAlive == true) {
            synchronized(holdLock) {
                frozen = false
                holdLock.notifyAll()
            }
            return
        }
        frozen = false

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
                parallelBranchStepper.close()
                hasHalted = isRunning
                isRunning = false
                println("[Engine $engineId] Stopped")
            }
        }
    }

    /**
     * The body of the engine thread. The first start has the [lifecycle] build the instances inside the [tickGate] and
     * resets the features as before any run; a later start continues with the instances and features as the [stop]
     * left them. Then the thread runs one tick inside the gate after the other, sleeping for the rest of [tickDelayMS]
     * outside the gate, until [stop]. An exception escaping the start or a tick ends the thread.
     */
    private fun runEngine() {
        if (!hasStartedBefore) {
            tickGate.duringTick { lifecycle.start() }
            hasStartedBefore = true

            //Feature cleanup
            //Unlock loop
            featureManager.onMessage(TickByTickFeature::class, "resume")
            //Reset tick counts
            featureManager.executeIfPresent<TickLimitFeature> { feature ->
                feature.incrementTick(emptyList())
            }
            //Reset History
            featureManager.executeIfPresent<HistoryFeature> { feature ->
                feature.resetHistory()
            }

            val count = lifecycle.instances.size
            traceLogger?.log(
                "*",
                EngineTraceLogger.TraceEventType.ENGINE_START,
                "Engine started with $count state machine(s), tickDelay=${tickDelayMS}ms"
            )
            println("[Engine $engineId] Started with $count state machine(s)")
        } else {
            traceLogger?.log(
                "*",
                EngineTraceLogger.TraceEventType.ENGINE_START,
                "Engine continued after tick $tickCount with ${lifecycle.instances.size} state machine(s)"
            )
            println("[Engine $engineId] Continued after tick $tickCount")
        }

        while (isRunning) {
            featureManager.executeIfPresent<TickByTickFeature> { feature ->
                feature.waiting = ::waitOutsideTick
                feature.onStatusChanged = reportEngineStatus
                waitingBetweenTicks = true
                try {
                    feature.evalPausedState()
                } finally {
                    waitingBetweenTicks = false
                }
            }
            if (!isRunning) break

            tickCount++
            val tickStart = System.currentTimeMillis()

            tickGate.duringTick {
                val start = System.nanoTime()
                runTick(tickCount)
                metrics.tickEnded(System.nanoTime() - start)
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
     * Runs one tick inside the [tickGate]: the queued engine commands, the purge of expired events, for each state
     * machine instance that has not ended its tick followed by its active-stack notification when the stack changed,
     * and at the end the changes of instances the tick asked for ([StateMachineLifecycle.applyChanges]). Every model
     * write and every push of the tick happens here, on the engine thread or on the threads of branches. Whether
     * [TrueParallelFeature] is on is read once at the start, so a switch takes effect at a tick boundary; at the end the
     * threads of branches that no longer step in parallel end.
     */
    private fun runTick(tickCount: Long) {
        val trueParallel = featureManager.getActiveFeatureOrNull<TrueParallelFeature>() != null
        val contexts = lifecycle.instances

        // Run queued engine-thread commands (e.g. forced resync) before ticking.
        while (true) {
            val cmd = commandQueue.poll() ?: break
            cmd(contexts)
        }

        featureManager.executeIfPresent<EventRecordingFeature> { feature ->
            //Record all events queued during this tick
            feature.recordQueuedEvents(tickCount, eventProcessor)
        }

        featureManager.executeIfPresent<EventInjectionFeature> { feature ->
            //Inject Events recorded for this tick (or earlier) and consume them
            feature.evalEventInjection(tickCount, eventProcessor)
        }

        featureManager.executeIfPresent<TickLimitFeature> { feature ->
            //Increment every currently visited state's tick counter
            feature.incrementTick(contexts)
        }

        // Purge expired events once per tick
        eventProcessor.purgeExpired()
        //println(eventProcessor.eventBus.getUnprocessedEvents("public", engineId).size)

        for (ctx in contexts) {
            if (ctx.hasEnded) continue
            traceLogger?.log(
                ctx.modelElementId,
                EngineTraceLogger.TraceEventType.TICK_START,
                "Tick #$tickCount",
                tickDetails(ctx)
            )
            tickStateMachine(ctx, trueParallel)
            traceLogger?.log(
                ctx.modelElementId,
                EngineTraceLogger.TraceEventType.TICK_END,
                "Tick #$tickCount completed",
                tickDetails(ctx)
            )

            // Notify observers only when the active stack actually changed, and never for an instance that has ended.
            if (ctx.hasEnded) continue
            val cur = ctx.branches.flatMap { it.stateStack }
            if (cur != ctx.lastReportedStack) {
                ctx.lastReportedStack = cur
                onActiveStackChanged?.invoke(ctx.modelElementId, cur)
            }
        }

        lifecycle.applyChanges()

        val live = lifecycle.instances
        parallelBranchStepper.retain(if (trueParallel) live.flatMap { it.branches } else emptyList())
    }

    /**
     * Runs [read] on the calling thread at the next tick boundary, waiting at most [timeoutMs] milliseconds for the
     * running tick to end; without a running tick it runs at once. The read sees the model after a whole number of
     * ticks with every push of those ticks enqueued. It also holds the read lock of the [modelQueryProcessor], so a
     * read during a halt at a breakpoint never sees a write of a branch that still steps on its own thread half done.
     *
     * @return the result of [read], or `null` when no tick boundary came within the timeout.
     */
    fun <T> readBetweenTicks(timeoutMs: Long, read: () -> T): T? =
        tickGate.betweenTicks(timeoutMs) { modelQueryProcessor.readModel(read) }

    /**
     * Trace details for a tick. "path" names the path of the only branch, or "paths" the paths of all
     * branches while the instance is split, in branch order; "stack" lists the active states across all
     * branches (equal to the single stack in simple mode); "currentStates" lists each branch's innermost
     * state, in the same order as "paths".
     */
    private fun tickDetails(ctx: SMContext): Map<String, String> = mapOf(
        ctx.pathDetail(),
        "stack" to ctx.branches.flatMap { it.stateStack }.joinToString(","),
        "currentStates" to ctx.branches.joinToString(",") { it.currentState() },
        "parallel" to ctx.isParallel().toString()
    )

    /**
     * Executes a single tick for one state machine context.
     *
     * The top-level loop stays small: in a parallel context a JOIN is checked first (it has priority
     * and can collapse the context back to simple); then every branch is ticked in order by the
     * [BranchRunner]. With [trueParallel] the branches of a parallel context instead do their steps at
     * the same time on threads of their own through the [parallelBranchStepper]. Only a simple context
     * may take a SPLIT, which the branch reports back so this method can carry out the structural
     * change. A simple context is just the same loop with one branch.
     */
    private fun tickStateMachine(ctx: SMContext, trueParallel: Boolean) {
        if (ctx.isParallel()) {
            val join = selectFireableJoin(ctx)
            if (join != null) {
                applyJoin(ctx, join)
                return
            }
            if (trueParallel) {
                parallelBranchStepper.step(ctx)
                return
            }
        }

        val allowSplit = !ctx.isParallel()
        for (branch in ctx.branches.toList()) {
            if (ctx.hasEnded) return
            val result = branchRunner.tick(ctx, branch, allowSplit)
            if (result is BranchTickResult.SplitRequested && !ctx.hasEnded) {
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
            val fireable = ctx.transitionProcessor.evaluateJoin(
                join, ctx.joinEventProcessor, ctx.branches.map { it.stateStack }
            )
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
     * every region can read it. A target that is a container state is gone down into (see [StateEntry]).
     */
    private fun applySplit(ctx: SMContext, parent: Branch, split: io.karpfen.dsl.states.SplitTransition, matchedEvent: Event?) {

        val smQueryHelper = ctx.smQueryHelper

        //Delay: only the states the split leaves can hold it back
        featureManager.executeIfPresent<TickLimitFeature> { feature ->
            val exited = split.toStates.flatMap { target ->
                StateStackHelper.computeExitSequence(parent.stateStack, smQueryHelper.getStateStackForState(target), target)
            }.distinct()
            if (feature.isTransitionBlocked(ctx.modelElementId, exited.mapNotNull { smQueryHelper.findStateByName(it) })) return
        }

        //Multiple state exit breakpoints
        featureManager.executeIfPresent<BreakpointFeature> { feature ->
            feature.evalStateExitBreakpointSplit(
                ctx.modelElementId,
                parent.stateStack.mapNotNull { stateName -> smQueryHelper.findStateByName(stateName) },
                split.toStates.map {
                    smQueryHelper.getStateStackForState(it)
                        .mapNotNull { stateName -> smQueryHelper.findStateByName(stateName) }
                }
            )
        }

        traceLogger?.log(
            ctx.modelElementId,
            EngineTraceLogger.TraceEventType.SPLIT_START,
            "${split.fromState} -> ${split.toStates.joinToString(",")}",
            mapOf("path" to parent.path.toString(), "from" to split.fromState, "to" to split.toStates.joinToString(","))
        )

        //Split transition start breakpoint
        featureManager.executeIfPresent<BreakpointFeature> { feature ->
            feature.evalTransitionStartBreakpoint(ctx.modelElementId, split, ctx.stateMachine.transitions.indexOfFirst { it === split })
        }

        if (eventConsumptionOnFire) matchedEvent?.let { parent.eventProcessor.consume(it) }

        val inherited = parent.eventProcessor.lineage()
        val newBranches = split.toStates.mapNotNull { target ->
            val targetStack = smQueryHelper.getStateStackForState(target)
            if (targetStack.isEmpty()) {
                System.err.println("[Engine] Split target '$target' not found — skipping it")
                return@mapNotNull null
            }
            val restingStack = stateEntry.restingStack(ctx, targetStack)
            val entry = StateStackHelper.computeEntrySequence(parent.stateStack, targetStack, target, restingStack)
            ctx.newBranch(restingStack, entry.toMutableList(), split, inherited).also { branch ->
                matchedEvent?.let { branch.scopedEventByState[target] = it }
            }
        }.toMutableList()

        if (newBranches.isEmpty()) return // nothing valid to split into; leave the context unchanged
        ctx.branches = newBranches

        //The states entered now count their ticks from this entry
        featureManager.executeIfPresent<TickLimitFeature> { feature ->
            feature.resetTicks(ctx.modelElementId, newBranches.flatMap { it.notEnteredSubstack })
        }

        traceLogger?.log(
            ctx.modelElementId,
            EngineTraceLogger.TraceEventType.SPLIT_FIRED,
            "${split.fromState} -> ${split.toStates.joinToString(",")}",
            mapOf(
                "path" to parent.path.toString(),
                "starts" to ctx.pathList(),
                "from" to split.fromState,
                "to" to split.toStates.joinToString(",")
            )
        )

        //Split transition end breakpoint
        featureManager.executeIfPresent<BreakpointFeature> { feature ->
            feature.evalTransitionEndBreakpoint(ctx.modelElementId, split, ctx.stateMachine.transitions.indexOfFirst { it === split })
        }
    }

    /**
     * Collapses all parallel branches into a single new branch entering the join target, turning the
     * context simple again. The new branch inherits the union of the joined branches' lineages so it
     * does not re-handle events the parallel phase already handled. A target that is a container state is
     * gone down into (see [StateEntry]).
     */
    private fun applyJoin(ctx: SMContext, fireable: Fireable) {

        val smQueryHelper = ctx.smQueryHelper
        val join = (fireable.transition as JoinTransition)

        //Delay
        featureManager.executeIfPresent<TickLimitFeature> { feature ->
            for (branch in ctx.branches) {
                if (feature.isTransitionBlocked(ctx.modelElementId, branch.stateStack.mapNotNull { smQueryHelper.findStateByName(it) })) return
            }
        }

        featureManager.executeIfPresent<BreakpointFeature> { feature ->
            //Additional entry breakpoint check, since split bypasses branchRunner.tick
            for (branch in ctx.branches) {
                for (state in branch.notEnteredSubstack.mapNotNull { smQueryHelper.findStateByName(it) }) {
                    feature.evalStateEntryBreakpoint(ctx.modelElementId, state)
                }
            }

            //Multiple state exit breakpoints
            feature.evalStateExitBreakpointJoin(
                ctx.modelElementId,
                ctx.branches.map { branch ->
                    branch.stateStack.mapNotNull { stateName ->
                        smQueryHelper.findStateByName(
                            stateName
                        )
                    }
                },
                smQueryHelper.getStateStackForState(join.toState)
                    .mapNotNull { stateName -> smQueryHelper.findStateByName(stateName) }
            )
        }

        traceLogger?.log(
            ctx.modelElementId,
            EngineTraceLogger.TraceEventType.JOIN_START,
            "${join.fromStates.joinToString(",")} -> ${join.toState}",
            mapOf("paths" to ctx.pathList(), "from" to join.fromStates.joinToString(","), "to" to join.toState)
        )

        //Join transition start breakpoint
        featureManager.executeIfPresent<BreakpointFeature> { feature ->
            feature.evalTransitionStartBreakpoint(ctx.modelElementId, join, ctx.stateMachine.transitions.indexOfFirst { it === join })
        }

        val targetStack = smQueryHelper.getStateStackForState(join.toState)
        if (targetStack.isEmpty()) {
            System.err.println("[Engine] Join target '${join.toState}' not found — skipping join")
            return
        }

        val ended = ctx.pathList()
        val inherited = ctx.branches.flatMap { it.eventProcessor.lineage() }.toSet()
        val restingStack = stateEntry.restingStack(ctx, targetStack)
        val entry = StateStackHelper.computeEntrySequence(emptyList(), targetStack, join.toState, restingStack)
        val newBranch = ctx.newBranch(restingStack, entry.toMutableList(), join, inherited)
        if (eventConsumptionOnFire) fireable.matchedEvent?.let { newBranch.eventProcessor.consume(it) }
        ctx.branches = mutableListOf(newBranch)

        //The states entered now count their ticks from this entry
        featureManager.executeIfPresent<TickLimitFeature> { feature ->
            feature.resetTicks(ctx.modelElementId, entry)
        }

        traceLogger?.log(
            ctx.modelElementId,
            EngineTraceLogger.TraceEventType.JOIN_FIRED,
            "${join.fromStates.joinToString(",")} -> ${join.toState}",
            mapOf(
                "ends" to ended,
                "path" to newBranch.path.toString(),
                "from" to join.fromStates.joinToString(","),
                "to" to join.toState
            )
        )

        //Join transition end breakpoint
        featureManager.executeIfPresent<BreakpointFeature> { feature ->
            feature.evalTransitionEndBreakpoint(ctx.modelElementId, join, ctx.stateMachine.transitions.indexOfFirst { it === join })
        }
    }

    /**
     * Runs the wait of the pause of [TickByTickFeature] with the [tickGate] open, as [TickGate.outsideTick] does, and
     * then holds the thread while a [stop] froze the engine, so a resume or ticks given while the engine is frozen take
     * effect only after the next [start]. Once [close] began the thread does not wait at all, so a breakpoint that a
     * thread reaches after the resume of [close], in the same tick or in a branch, cannot hold the end of the engine.
     */
    private fun waitOutsideTick(wait: () -> Unit) {
        if (closed) return
        tickGate.outsideTick {
            parkedInWait = true
            try {
                wait()
                awaitStart()
            } finally {
                parkedInWait = false
            }
        }
    }

    /** Holds the calling thread while the engine is frozen and not closed. */
    private fun awaitStart() {
        synchronized(holdLock) {
            while (frozen && !closed) holdLock.wait()
        }
    }

    /**
     * Freezes the run: the engine ticks no more, and everything stays for a later [start], which continues with the
     * next tick. A running tick ends first; the instances, their stacks and branches, the tick number, the features and
     * the events on the bus stay as they are, and the bus stops counting the time to live of its events.
     *
     * When the engine runs, its thread ends after the running tick, and with it the threads of the branches of
     * [TrueParallelFeature]. When a thread of the engine waits for the pause of [TickByTickFeature], between two ticks
     * or at a breakpoint inside a tick, it stays where it is, held, so the pause and the halted tick are still there at
     * the start; between two ticks the threads of branches end. A resume or ticks given while the engine is frozen
     * release the wait only after the next start. The call returns once the thread ended or is held. A thread that
     * neither ends nor waits within 5 s is interrupted. Stopping an engine that does not run changes nothing.
     */
    fun stop() = synchronized(lifeLock) {
        val t = executionThread
        if (t == null || !t.isAlive || frozen) {
            isRunning = false
            return
        }
        isRunning = false
        try {
            val deadline = System.currentTimeMillis() + STOP_WAIT_MS
            while (t.isAlive && !parkedInWait && System.currentTimeMillis() < deadline) t.join(STOP_POLL_MS)
            if (t.isAlive && !parkedInWait) {
                t.interrupt()
                t.join(STOP_WAIT_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (t.isAlive) {
            synchronized(holdLock) { frozen = true }
            if (waitingBetweenTicks) parallelBranchStepper.closeIfIdle()
            traceLogger?.log("*", EngineTraceLogger.TraceEventType.ENGINE_STOP, "Engine stopped after $tickCount ticks")
        }
        eventBus.freeze()
    }

    /**
     * Ends the engine for good: stops the run, releases a thread held by [stop] and the pause of [TickByTickFeature] so
     * that the thread can end, and waits for it: the running tick ends, and the threads of branches end with the engine
     * thread. A thread that has not ended within 5 s, as one whose EVAL waits for Python, first loses its Python
     * sessions, which ends that wait, and is then interrupted. At the end the Python sessions and the trace log are
     * closed; the trace file stays on disk. The engine does not start again.
     */
    fun close() = synchronized(lifeLock) {
        closed = true
        isRunning = false
        synchronized(holdLock) { holdLock.notifyAll() }
        featureManager.onMessage(TickByTickFeature::class, "resume")
        executionThread?.let { t ->
            try {
                t.join(STOP_WAIT_MS)
                if (t.isAlive) {
                    lifecycle.abort()
                    t.interrupt()
                    t.join(STOP_WAIT_MS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        lifecycle.close()
        traceLogger?.close()
    }

    /**
     * Tells the features, the trace log and the [stateMachineObserver] about every instance that joins and leaves. An
     * instance that leaves takes its histories, tick counts and breakpoint overrides with it, so an instance that later
     * runs on the same object starts without them.
     *
     * The trace log gets one entry per change: `STATE_MACHINE_ATTACHED` for an instance that joins on its own,
     * `STATE_MACHINE_DETACHED` for one that leaves with no successor, and a single `STATE_MACHINE_EXCHANGED` for an
     * instance that `EXCHANGE` put at the place of another, written when the new one joins. Every joining instance is
     * followed by an `INITIAL_STATE` entry with its initial stack. The observer still learns an exchange as the old
     * instance detached and the new one attached.
     */
    private inner class InstanceReporter : StateMachineLifecycleListener {

        override fun joined(ctx: SMContext, predecessor: SMContext?) {
            val id = ctx.modelElementId
            if (predecessor == null) {
                traceLogger?.log(
                    id,
                    EngineTraceLogger.TraceEventType.STATE_MACHINE_ATTACHED,
                    "State machine ${describe(ctx)} attached",
                    mapOf("stateMachine" to (ctx.definitionName ?: ""), "path" to ctx.pathList())
                )
            } else {
                traceLogger?.log(
                    id,
                    EngineTraceLogger.TraceEventType.STATE_MACHINE_EXCHANGED,
                    "State machine ${describe(predecessor)} exchanged for ${describe(ctx)}",
                    mapOf(
                        "ended" to (predecessor.definitionName ?: ""),
                        "started" to (ctx.definitionName ?: ""),
                        "ends" to predecessor.pathList(),
                        "path" to ctx.pathList()
                    )
                )
            }
            val stack = ctx.branches.single().stateStack
            traceLogger?.log(
                id,
                EngineTraceLogger.TraceEventType.INITIAL_STATE,
                "Initial state stack: ${stack.joinToString(" > ")}",
                mapOf("path" to ctx.pathList(), "stack" to stack.joinToString(","))
            )
            stateMachineObserver?.onStateMachineAttached(id, ctx.definitionName)
        }

        override fun left(ctx: SMContext, successor: SMContext?) {
            val id = ctx.modelElementId
            featureManager.executeIfPresent<HistoryFeature> { it.forget(id) }
            featureManager.executeIfPresent<TickLimitFeature> { it.forget(id) }
            featureManager.executeIfPresent<BreakpointFeature> { it.forget(id) }
            if (successor == null) {
                traceLogger?.log(
                    id,
                    EngineTraceLogger.TraceEventType.STATE_MACHINE_DETACHED,
                    "State machine ${describe(ctx)} detached",
                    mapOf("stateMachine" to (ctx.definitionName ?: ""), ctx.pathDetail())
                )
            }
            stateMachineObserver?.onStateMachineDetached(id, ctx.definitionName)
        }

        /** Names the state machine of [ctx] for a message: its definition in quotes, or the run configuration. */
        private fun describe(ctx: SMContext): String =
            ctx.definitionName?.let { "'$it'" } ?: "of the run configuration"
    }

    /** The timing of [stop] and [close]. */
    private companion object {
        /** How long [stop] and [close] wait for the engine thread before they interrupt it, and after that. */
        const val STOP_WAIT_MS = 5_000L

        /** How often [stop] looks whether the engine thread ended or is held. */
        const val STOP_POLL_MS = 10L
    }
}