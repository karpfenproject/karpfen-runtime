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
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.websocket.ClientSessionManager
import java.util.concurrent.LinkedBlockingQueue

class EnvironmentThread(val environment: Environment) : Runnable {

    private val eventQueue = LinkedBlockingQueue<Event>()
    private var isRunning = false

    val engine = Engine(
        environment.metamodel!!,
        environment.model!!,
        environment.stateMachines,
        environment.tickDelayMS
    )

    fun setup() {
        val clientSessionManager = EnvironmentHandler.clientSessionManager

        // Register observation listeners for object changes
        for (observation in environment.objectObservations) {
            engine.registerDataObservationListener(observation.observedObjectId, object : DataObservationListener {
                override fun onChange(clientId: String, objectId: String) {
                    // For now, just notify with empty value
                    // TODO: Get the actual new value from the object
                    clientSessionManager.notifyObjectChange(environment.key, objectId, "")
                }
            })
        }

        println("[EnvironmentThread] Setup complete for environment ${environment.key}")
    }

    fun getEventQueue(): LinkedBlockingQueue<Event> = eventQueue

    fun stop() {
        isRunning = false
    }

    override fun run() {
        isRunning = true
        println("[EnvironmentThread] Running for environment ${environment.key}")

        try {
            while (isRunning) {
                // Poll for incoming events from WebSocket clients
                val event = eventQueue.poll(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (event != null) {
                    println("[EnvironmentThread] Processing event from domain: ${event.domain}")
                    // TODO: Forward event to the engine for processing
                    // engine.processEvent(event)
                }
            }
        } catch (e: InterruptedException) {
            println("[EnvironmentThread] Interrupted")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            println("[EnvironmentThread] Error: ${e.message}")
            e.printStackTrace()
        } finally {
            println("[EnvironmentThread] Stopped for environment ${environment.key}")
        }
    }
}

