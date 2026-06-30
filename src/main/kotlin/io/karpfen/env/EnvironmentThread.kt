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

    private val incomingExternalEvents = LinkedBlockingQueue<Event>()
    @Volatile
    private var isRunning = false

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

    fun setup() {
        val clientSessionManager = EnvironmentHandler.clientSessionManager

        // Register a ModelChangePublisher that pushes JSON updates to subscribed WebSocket clients.
        engine.modelQueryProcessor.addChangePublisher(object : io.karpfen.io.karpfen.exec.ModelChangePublisher {
            override fun onObjectChanged(objectId: String, jsonValue: String) {
                clientSessionManager.notifyObjectChange(environment.key, objectId, jsonValue)
            }

            override fun onObjectDeleted(objectId: String) {
                clientSessionManager.notifyObjectDeleted(environment.key, objectId)
            }
        })

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

        // Push ONLY active-state changes to observatory-subscribed WebSocket clients (the per-tick
        // trace stream is intentionally not forwarded — it was pure overhead). The engine fires this
        // exactly once per real state change (initial / transition / split / join / forced resync).
        engine.onActiveStackChanged = { modelElementId, stack ->
            val payload = org.json.JSONObject()
                .put("modelElementId", modelElementId)
                .put("stack", org.json.JSONArray(stack))
                .toString()
            clientSessionManager.notifyObservatory(environment.key, modelElementId, "stateChange", payload)
        }

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

    fun stop() {
        isRunning = false
        engine.stop()
    }

    override fun run() {
        isRunning = true
        println("[EnvironmentThread] Running for environment ${environment.key}")

        try {
            // Start the engine (it runs in its own thread)
            engine.start()

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
}
