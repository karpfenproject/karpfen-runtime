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
package io.karpfen.env

import io.karpfen.DataObservationListener
import io.karpfen.Engine
import io.karpfen.EngineTraceLogger
import io.karpfen.io.karpfen.exec.LruResultCache
import io.karpfen.io.karpfen.exec.ModelJsonWriter
import io.karpfen.io.karpfen.exec.ResultCache
import io.karpfen.io.karpfen.features.runtime.TickByTickFeature
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.metrics.RunMetricsSnapshot
import io.karpfen.observation.ActiveStackRecord
import io.karpfen.observation.ChangeRevision
import io.karpfen.observation.EnvironmentPhase
import io.karpfen.observation.ModelSnapshot
import io.karpfen.observation.ObservationRelay
import io.karpfen.observation.ObservedEnvironment
import io.karpfen.observation.StateMachineRecord
import java.util.concurrent.LinkedBlockingQueue

/**
 * One activated environment at run time: its [engine], the [sharedEventBus], the runner thread of its event loop and
 * what model observation reads.
 *
 * [setup] wires the engine to the environment's WebSocket clients, [launch] starts the engine and the runner whose
 * event loop ([run]) drains external events onto the bus while the engine ticks on its own thread, and [stop] freezes
 * both: the engine keeps everything for the next [launch], which continues the run, and the events that clients send
 * meanwhile wait in the queue of the runner. The connections of the clients stay open. [dispose] ends the environment
 * for good. The environment owns its one runner, its run state, the [revisions] that pushes and snapshots share and
 * the record of [activeStacks]; it answers the [phase] and the snapshots of model observation.
 *
 * @property environment the configured environment this thread runs.
 * @property stateMachines the flattened state machines of [environment], which the engine runs.
 * @property traceLogger the engine's trace logger, or `null` for no tracing.
 */
class EnvironmentThread(
    val environment: Environment,
    val stateMachines: ComposedStateMachines,
    val traceLogger: EngineTraceLogger? = null
) : Runnable, ObservedEnvironment {

    /**
     * One shared [EventBus] per environment so that all Engine instances within it
     * exchange events via the same bus.
     */
    val sharedEventBus: EventBus = EventBus(
        defaultTtlMs = environment.eventTtlMs
    )

    /**
     * Counts the observations of this environment's engine for push messages and snapshots. It lives as long as the
     * activation, so a start after a stop continues counting.
     */
    val revisions = ChangeRevision()

    /** The last reported active stack of every state machine of this environment, read by snapshots. */
    val activeStacks = ActiveStackRecord()

    /** The state machine instances of this environment as its clients were told, read by snapshots. */
    val stateMachineRecord = StateMachineRecord()

    private val incomingExternalEvents = LinkedBlockingQueue<Event>()
    @Volatile
    private var isRunning = false

    /** Where this environment is in its start and stop cycle; changed only by [launch] and [stop]. */
    @Volatile
    private var runState = RunState.READY

    /** The thread running this environment's event loop since the latest [launch], or `null` before the first. */
    @Volatile
    private var runner: Thread? = null

    /**
     * True when the event loop of the latest [launch] ended although no [stop] had asked it to, through an exception,
     * an error or an interrupt. The runner sets it as its loop ends, and [launch] resets it before the next runner
     * exists. A runner that ends because of [stop] never sets it.
     */
    @Volatile
    private var runnerEndedOnItsOwn = false

    /** The time of the latest [stop] that froze the environment, for the events that clients sent while it was frozen. */
    private var frozenAt: Long = 0L

    /** True once [dispose] began; from then on [launch] and [stop] do nothing and events from clients are dropped. */
    @Volatile
    private var disposed = false

    /** The relay that [setup] wires, which pushes the notices of [stop] and [launch]. */
    private var relay: ObservationRelay? = null

    val engine = Engine(
        environment.metamodel!!,
        environment.model!!,
        stateMachines.attached,
        environment.tickDelayMS,
        sharedEventBus,
        traceLogger = traceLogger,
        eventConsumptionOnFire = environment.eventConsumptionOnFire,
        eventMetamodel = environment.eventMetamodel,
        featureManager = environment.featureManager,
        stateMachineDefinitions = stateMachines.definitions,
        resultCache = if (environment.resultCaching) {
            LruResultCache(environment.resultCacheMaxEntries, environment.resultCacheMaxBytes)
        } else {
            ResultCache.NONE
        }
    )

    /**
     * The phase derived from the run state, the engine and the runner, read without a lock while [launch] or [stop]
     * may be under way.
     *
     * Before the first [launch] the environment is activated and after [stop] it is stopped, but in both run states a
     * ticking engine ([Engine.isTicking]) still counts as running: the engine has already started while [launch] has
     * not yet set the run state, and its last tick may still run while [stop] waits for it. An engine thread that the
     * stop holds where it waits for a pause does not tick. Once launched, the environment is halted
     * when it ended on its own: the engine thread of the latest start ended on its own, or an exception or error ended
     * the runner's event loop without a [stop], and the runner's end stopped the engine. It is running otherwise.
     *
     * Both signs of an end on its own are flags that only an end without a [stop] sets, not the liveness of a thread.
     * So a reader that took the run state as running just before a [stop] still reports running, which was true a
     * moment ago, and never halted because the threads of the stopped run have ended meanwhile.
     */
    override val phase: EnvironmentPhase
        get() = when (runState) {
            RunState.READY -> if (engine.isTicking) EnvironmentPhase.RUNNING else EnvironmentPhase.ACTIVATED
            RunState.RUNNING -> if (hasEndedOnItsOwn) EnvironmentPhase.HALTED else EnvironmentPhase.RUNNING
            RunState.STOPPED -> if (engine.isTicking) EnvironmentPhase.RUNNING else EnvironmentPhase.STOPPED
        }

    /**
     * True when the launched environment ended without a [stop]: the engine thread of the latest start ended on its
     * own, or the event loop of the latest [launch] did.
     */
    private val hasEndedOnItsOwn: Boolean
        get() = engine.hasHalted || runnerEndedOnItsOwn

    /**
     * Reads the snapshot through the engine's tick gate: the phase, the revision of the last observation, the recorded
     * active stacks, every root object written like an `objectChanged` value and the recorded state machine instances,
     * all between two ticks.
     */
    override fun snapshotBetweenTicks(timeoutMs: Long): ModelSnapshot? =
        engine.readBetweenTicks(timeoutMs) {
            ModelSnapshot(
                environment.key,
                phase,
                revisions.current(),
                activeStacks.current(),
                engine.model.objects.map(ModelJsonWriter::write),
                stateMachineRecord.current()
            )
        }

    /** The numbers of the engine, read from its [Engine.metrics] without waiting for a tick boundary. */
    override fun metrics(): RunMetricsSnapshot = engine.metrics.snapshot()

    override val resultCaching: Boolean
        get() = engine.resultCache.enabled

    /**
     * Wires the engine to the environment's WebSocket clients before the first start.
     *
     * One [ObservationRelay] receives every object change and deletion of the model and every active-stack change of
     * the engine, assigns each one the next of [revisions], records the stacks in [activeStacks], and hands the push
     * to the session manager. As the publication listener of [sharedEventBus] it also turns every published event into
     * a `domainEvent` push, and it turns every status report of the engine into an `engineStatus` push to every
     * connected client. The object observers and domain listeners registered before activation are subscribed
     * here.
     */
    fun setup() {
        val clientSessionManager = EnvironmentHandler.clientSessionManager

        val relay = ObservationRelay(environment.key, clientSessionManager, revisions, activeStacks, stateMachineRecord)
        this.relay = relay
        engine.modelQueryProcessor.addChangePublisher(relay)
        sharedEventBus.addPublicationListener(relay)

        // Register DataObservation listeners (from the Environment configuration).
        for (observation in environment.objectObservations) {
            clientSessionManager.subscribeToObject(environment.key, observation.observingClientId, observation.observedObjectId)
            engine.registerDataObservationListener(
                observation.observedObjectId,
                object : DataObservationListener {
                    override fun onChange(clientId: String, objectId: String) {
                        // The ModelChangePublisher above already pushes the JSON value,
                        // so nothing extra needed here.
                    }
                }
            )
        }

        // Subscribe domain listeners so that events arriving on their domain are forwarded.
        for (dl in environment.domainListeners) {
            clientSessionManager.subscribeToDomain(environment.key, dl.clientId, dl.domain)
        }

        engine.onActiveStackChanged = relay::onActiveStackChanged
        engine.onEngineStatusChanged = relay::onEngineStatusChanged
        engine.stateMachineObserver = relay

        println("[EnvironmentThread] Setup complete for environment ${environment.key}")
    }

    /** Accepts an externally produced Event and queues it for publication on the bus; dropped once disposed. */
    fun acceptExternalEvent(event: Event) {
        if (disposed) return
        incomingExternalEvents.offer(event)
    }

    /**
     * Forces one state machine into [leaf], clearing its pending events. Drops any queued external
     * events for the whole env first (so an in-flight event can't fight the snap), then dispatches the
     * stack change onto the engine thread. Used by the manual "resync" API.
     */
    fun forceActiveState(modelElement: String, leaf: String) {
        incomingExternalEvents.clear()
        engine.forceActiveState(modelElement, leaf)
    }

    /**
     * Starts this environment: its engine and the one runner thread of its event loop.
     *
     * The environment owns its runner, so starting never adds a second event loop:
     * - running with a live runner and a ticking engine: nothing happens;
     * - running with a live runner but an engine whose thread ended on its own: the engine continues;
     * - ready: the engine builds and reports the instances of the first start, the loop flag is set before the runner
     *   exists (so a stop can no longer be overwritten by the runner's first line), a new non-daemon runner named
     *   `EnvironmentThread-<key>` is started, and the run state becomes running once both have started (see [phase]);
     * - stopped, or running without a live runner: the same, but the engine continues the frozen run with the next
     *   tick, the events that clients sent while the environment was stopped get back the time it was stopped, and
     *   after a stop every connected client is told through `engineStatus` with the phase `running`.
     *
     * A disposed environment starts nothing.
     *
     * @return `true` when something was started, `false` when the environment was already running or is disposed.
     */
    @Synchronized
    fun launch(): Boolean {
        if (disposed) return false
        if (runState == RunState.RUNNING && runner?.isAlive == true) {
            if (engine.isTicking) {
                return false
            }
            engine.start()
            return true
        }
        val continues = runState == RunState.STOPPED
        if (continues) {
            val thawedAt = System.currentTimeMillis()
            incomingExternalEvents.forEach { it.creditFreeze(frozenAt, thawedAt) }
        }
        isRunning = true
        runnerEndedOnItsOwn = false
        engine.start()
        runner = Thread(this, "EnvironmentThread-${environment.key}").apply {
            isDaemon = false
            start()
        }
        runState = RunState.RUNNING
        if (continues) relay?.onPhaseChanged(EnvironmentPhase.RUNNING, engineStatus())
        return true
    }

    /**
     * Stops this environment as a freeze: marks it stopped, ends the event loop (waiting at most [RUNNER_JOIN_MS]
     * milliseconds for the runner), and freezes the engine, which keeps everything for the next [launch]; see
     * [Engine.stop]. Events that clients send from now on wait in the queue of the runner. Every connected client is
     * told through `engineStatus` with the phase `stopped`; the connections stay open. An environment that was never
     * started or is stopped already stays as it is.
     *
     * @return `true` when the environment was stopped, `false` when there was nothing to stop.
     */
    @Synchronized
    fun stop(): Boolean {
        if (disposed || runState != RunState.RUNNING) return false
        runState = RunState.STOPPED
        isRunning = false
        runner?.join(RUNNER_JOIN_MS)
        engine.stop()
        frozenAt = System.currentTimeMillis()
        relay?.onPhaseChanged(EnvironmentPhase.STOPPED, engineStatus())
        return true
    }

    /**
     * Ends this environment for good, when it is dropped or the runtime shuts down: ends the event loop, drops the
     * events that wait in its queue and closes the engine, which ends its thread and the threads of its branches,
     * releases a pause or a halt, and closes the Python sessions and the trace log; see [Engine.close]. A [launch] or
     * [stop] under way finishes first, and the environment does not start again. Disposing twice changes nothing.
     */
    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        runState = RunState.STOPPED
        isRunning = false
        runner?.join(RUNNER_JOIN_MS)
        incomingExternalEvents.clear()
        engine.close()
    }

    /**
     * The status of the engine as the feature TickByTick reports it, or `{"paused":false,"waiting":false}` while that
     * feature is off.
     */
    private fun engineStatus(): String =
        environment.featureManager.getActiveFeatureOrNull<TickByTickFeature>()?.engineStatus()
            ?: "{\"paused\":false,\"waiting\":false}"

    /**
     * The event loop of the runner thread that [launch] starts: drains external events onto the shared bus and purges
     * expired events until [stop]. The engine ticks on its own thread. When the loop ends without a [stop], through an
     * exception, an error or an interrupt, the engine is stopped and the environment is halted (see [phase]).
     */
    override fun run() {
        println("[EnvironmentThread] Running for environment ${environment.key}")

        try {
            while (isRunning) {
                // Drain all queued external events into the shared bus
                val batch = mutableListOf<Event>()
                incomingExternalEvents.drainTo(batch)
                for (event in batch) {
                    engine.receiveExternalEvent(event)
                }

                // Purge expired events periodically
                sharedEventBus.purgeExpired()

                Thread.sleep(10)
            }
        } catch (e: InterruptedException) {
            println("[EnvironmentThread] Interrupted")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            println("[EnvironmentThread] Error: ${e.message}")
            e.printStackTrace()
        } finally {
            if (isRunning) {
                runnerEndedOnItsOwn = true
                engine.stop()
            }
            println("[EnvironmentThread] Stopped for environment ${environment.key}")
        }
    }

    /** The start and stop cycle of an environment: built, started by [launch], stopped by [stop]. */
    private enum class RunState {

        /** Built and set up, never launched. */
        READY,

        /** Launched by the latest [launch], with no [stop] since. */
        RUNNING,

        /** Stopped by [stop]; a later [launch] continues the frozen run. */
        STOPPED
    }

    /** The timing of the start and stop cycle. */
    private companion object {
        /** How long [stop] waits for the runner thread to finish its loop. */
        const val RUNNER_JOIN_MS = 1_000L
    }
}
