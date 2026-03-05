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
package io.karpfen.server

import io.karpfen.env.EnvironmentHandler
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.websocket.ClientSessionManager
import io.karpfen.websocket.WebSocketMessage
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.time.Duration

/**
 * Ktor HTTP Server with WebSocket support.
 */
class KtorServer(
    private val port: Int = 8080,
    private val host: String = "127.0.0.1",
    private val sessionManager: ClientSessionManager = EnvironmentHandler.clientSessionManager
) {
    private var engine: ApplicationEngine? = null

    fun start() {
        engine = embeddedServer(Netty, port = port, host = host) {
            HTTPRoutes.configure(this)
            configureWebSocket()
        }.start(wait = false)
        println("[KtorServer] Started on http://$host:$port")
    }

    private fun Application.configureWebSocket() {
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(60)
            timeout = Duration.ofSeconds(15)
            masking = false
        }

        routing {
            webSocket("/ws") {
                var authenticatedEnvKey: String? = null
                var authenticatedAccessKey: String? = null

                try {
                    // Expect first message to contain authentication (clientId:envKey:accessKey)
                    val firstFrame = incoming.receive()
                    if (firstFrame !is Frame.Text) {
                        println("[WebSocket] Invalid first frame")
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "First message must be text"))
                        return@webSocket
                    }

                    val authMessage = firstFrame.readText()
                    val parts = authMessage.split(":")
                    if (parts.size != 3) {
                        println("[WebSocket] Invalid auth format. Expected clientId:envKey:accessKey")
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid auth format"))
                        return@webSocket
                    }

                    val clientId = parts[0]
                    val envKey = parts[1]
                    val accessKey = parts[2]

                    val attached = sessionManager.verifyAndAttachSession(clientId, envKey, accessKey, this)
                    if (!attached) {
                        println("[WebSocket] Authentication failed for client $clientId")
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid credentials"))
                        return@webSocket
                    }

                    authenticatedEnvKey = envKey
                    authenticatedAccessKey = accessKey
                    println("[WebSocket] Authenticated client $clientId for environment $envKey")

                    // Process incoming messages
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            println("[WebSocket] Received from $clientId: $text")

                            // Try to parse as WebSocketMessage
                            val message = WebSocketMessage.fromJson(text)
                            if (message != null) {
                                // Get the event queue for this environment
                                val envThread = EnvironmentHandler.executionThreads[envKey]
                                if (envThread != null) {
                                    // Convert WebSocketMessage to Event
                                    val event = Event(message.messageType, message.payload)
                                    envThread.getEventQueue().offer(event)
                                    println("[WebSocket] Queued event for environment $envKey")
                                } else {
                                    println("[WebSocket] No active environment thread for $envKey")
                                }
                            } else {
                                println("[WebSocket] Could not parse message: $text")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("[WebSocket] Error: ${e.message}")
                    e.printStackTrace()
                } finally {
                    if (authenticatedEnvKey != null && authenticatedAccessKey != null) {
                        sessionManager.unregisterClientSession(authenticatedEnvKey!!, authenticatedAccessKey!!)
                    }
                    println("[WebSocket] Connection closed")
                }
            }
        }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 300, timeoutMillis = 5000)
        println("[KtorServer] Stopped")
    }
}
