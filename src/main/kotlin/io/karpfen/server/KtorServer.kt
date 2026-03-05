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

import io.karpfen.websocket.WebSocketManager
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
    private val webSocketManager: WebSocketManager
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
                val remoteHost = call.request.headers["Host"] ?: "unknown"
                println("[WebSocket] New connection from $remoteHost")
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            println("[WebSocket] Received: $text")

                            // Try to parse as WebSocketMessage
                            val message = WebSocketMessage.fromJson(text)
                            if (message != null) {
                                webSocketManager.enqueueMessage(message)
                            } else {
                                println("[WebSocket] Could not parse message: $text")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("[WebSocket] Error: ${e.message}")
                } finally {
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
