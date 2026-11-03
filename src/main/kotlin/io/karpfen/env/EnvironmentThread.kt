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
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.observation.ActiveStackRecord
import io.karpfen.observation.ChangeRevision
import io.karpfen.observation.ObservationRelay
import java.util.concurrent.LinkedBlockingQueue

class EnvironmentThread(
    val environment: Environment,
    val traceLogger: EngineTraceLogger? = null
) : Runnable {

    /**
     * One shared [EventBus] per environment so that all Engine instances within it
     * exchange events via the same bus.
     */
    val sharedEventBus: EventBus = EventBus(
        defaultTtlMs = environment.eventTtlMs
    )

    /**
     * Counts the observations of this environment's engine for push messages and snapshots. It lives as long as the
     * activation, so a restart after a stop continues counting.
     */
    val revisions = ChangeRevision()

    /** The last reported active stack of every state machine of this environment, read by snapshots. */
    val activeStacks = ActiveStackRecord()

    private val incomingExternalEvents = LinkedBlockingQueue<Event>()
    @Volatile
    private var isRunning = false

    /** Where this environment is in its start and stop cycle; changed only by [launch] and [stop]. */
    @Volatile
    private var runState = RunState.READY

    /** The thread running this environment's event loop since the latest [launch], or `null` before the first. */
    @Volatile
    private var runner: Thread? = null

    val engine = Engine(
        environment.metamodel!!,
        environment.model!!,
        environment.stateMachines,
        environment.tickDelayMS,
        sharedEventBus,
        traceLogger = traceLogger,
        eventConsumptionOnFire = environment.eventConsumptionOnFire,
        eventMetamodel = environment.eventMetamodel
    )

    /**
     * Wires the engine to the environment's WebSocket clients before the first start.
     *
     * One [ObservationRelay] receives every object change and deletion of the model and every active-stack change of
     * the engine, assigns each one the next of [revisions], records the stacks in [activeStacks], and hands the push
     * to the session manager. As the publication listener of [sharedEventBus] it also turns every published event into
     * a `domainEvent` push. The object observers and domain listeners registered before activation are subscribed
     * here.
     */
    fun setup() {
        val clientSessionManager = EnvironmentHandler.clientSessionManager

        val relay = ObservationRelay(environment.key, clientSessionManager, revisions, activeStacks)
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

        println("[EnvironmentThread] Setup complete for environment ${environment.key}")
    }

    /** Accepts an externally produced Event and queues it for publication on the bus. */
    fun acceptExternalEvent(event: Event) {
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
     * - running with a live runner but an engine whose thread ended on its own: the engine is started again;
     * - ready, stopped, or running without a live runner: the recorded active stacks are cleared, the loop flag is
     *   set before the runner exists (so a stop can no longer be overwritten by the runner's first line), the engine
     *   is started from its initial stacks over the current model, and a new non-daemon runner named
     *   `EnvironmentThread-<key>` is started.
     *
     * @return `true` when something was started, `false` when the environment was already running.
     */
    @Synchronized
    fun launch(): Boolean {
        if (runState == RunState.RUNNING && runner?.isAlive == true) {
            if (engine.isTicking) {
                return false
            }
            engine.start()
            return true
        }
        activeStacks.clear()
        runState = RunState.RUNNING
        isRunning = true
        engine.start()
        runner = Thread(this, "EnvironmentThread-${environment.key}").apply {
            isDaemon = false
            start()
        }
        return true
    }

    /**
     * Stops this environment: marks it stopped, ends the event loop, stops the engine (joining its thread), and then
     * waits at most [RUNNER_JOIN_MS] milliseconds for the runner to finish. The model stays as the engine left it.
     */
    @Synchronized
    fun stop() {
        runState = RunState.STOPPED
        isRunning = false
        engine.stop()
        runner?.join(RUNNER_JOIN_MS)
    }

    /**
     * The event loop of the runner thread that [launch] starts: drains external events onto the shared bus and purges
     * expired events until [stop]. The engine ticks on its own thread.
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
            engine.stop()
            println("[EnvironmentThread] Stopped for environment ${environment.key}")
        }
    }

    /** The start and stop cycle of an environment: built, started by [launch], stopped by [stop]. */
    private enum class RunState { READY, RUNNING, STOPPED }

    private companion object {
        /** How long [stop] waits for the runner thread to finish its loop. */
        const val RUNNER_JOIN_MS = 1_000L
    }
}
