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
        eventConsumptionOnFire = environment.eventConsumptionOnFire
    )

    fun setup() {
        val clientSessionManager = EnvironmentHandler.clientSessionManager

        // Register a ModelChangePublisher that pushes JSON updates to subscribed WebSocket clients.
        engine.modelQueryProcessor.addChangePublisher { objectId, jsonValue ->
            clientSessionManager.notifyObjectChange(environment.key, objectId, jsonValue)
        }

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

        // Wire up trace logger to push entries to observatory-subscribed WebSocket clients.
        val useSimpleTrace = EnvironmentHandler.simpleTrace
        traceLogger?.addTraceListener { entry ->
            val escapedMsg = entry.message.replace("\\", "\\\\").replace("\"", "\\\"")
            val payload: String
            if (useSimpleTrace) {
                val short = buildSimpleMessage(entry)
                val escapedShort = short.replace("\\", "\\\\").replace("\"", "\\\"")
                payload = """{"timestamp":${entry.timestamp},"modelElementId":"${entry.modelElementId}","eventType":"${entry.eventType.name}","message":"$escapedShort"}"""
            } else {
                val detailsJson = entry.details.entries.joinToString(",") { (k, v) ->
                    val ek = k.replace("\\", "\\\\").replace("\"", "\\\"")
                    val ev = v.replace("\\", "\\\\").replace("\"", "\\\"")
                    "\"$ek\":\"$ev\""
                }
                payload = """{"timestamp":${entry.timestamp},"modelElementId":"${entry.modelElementId}","eventType":"${entry.eventType.name}","message":"$escapedMsg","details":{$detailsJson}}"""
            }
            clientSessionManager.notifyObservatory(environment.key, entry.modelElementId, "trace", payload)
        }

        println("[EnvironmentThread] Setup complete for environment ${environment.key}")
    }

    /**
     * Builds a concise one-line message for simple trace mode.
     */
    private fun buildSimpleMessage(entry: EngineTraceLogger.TraceEntry): String {
        val d = entry.details
        return when (entry.eventType) {
            EngineTraceLogger.TraceEventType.TICK_START -> "tick #${d["tick"] ?: "?"} [${d["stack"] ?: ""}]"
            EngineTraceLogger.TraceEventType.TICK_END -> "tick end"
            EngineTraceLogger.TraceEventType.INITIAL_STATE -> "initial -> ${d["stack"] ?: entry.message}"
            EngineTraceLogger.TraceEventType.STATE_ENTRY_EXEC -> "entry: ${entry.message}"
            EngineTraceLogger.TraceEventType.STATE_ENTRY_SKIP -> "entry skip: ${entry.message}"
            EngineTraceLogger.TraceEventType.STATE_DO_EXEC -> "do: ${entry.message}"
            EngineTraceLogger.TraceEventType.STATE_DO_SKIP -> "do skip: ${entry.message}"
            EngineTraceLogger.TraceEventType.TRANSITION_FIRED -> "${d["from"] ?: "?"} -> ${d["to"] ?: "?"}"
            EngineTraceLogger.TraceEventType.TRANSITION_SKIPPED_LOOP -> "skip loop: ${d["from"] ?: "?"} -> ${d["to"] ?: "?"}"
            EngineTraceLogger.TraceEventType.TRANSITION_CHECK -> "check: ${entry.message}"
            EngineTraceLogger.TraceEventType.EVENT_RECEIVED -> "event in: ${entry.message}"
            EngineTraceLogger.TraceEventType.EVENT_CONSUMED -> "event ok: ${entry.message}"
            EngineTraceLogger.TraceEventType.ACTION_ERROR -> "ERR: ${entry.message}"
            EngineTraceLogger.TraceEventType.ENGINE_ERROR -> "ERR: ${entry.message}"
            EngineTraceLogger.TraceEventType.ENGINE_START -> "engine start"
            EngineTraceLogger.TraceEventType.ENGINE_STOP -> "engine stop"
        }
    }

    /** Accepts an externally produced Event and queues it for publication on the bus. */
    fun acceptExternalEvent(event: Event) {
        incomingExternalEvents.offer(event)
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
