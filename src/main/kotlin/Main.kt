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
import io.karpfen.config.ApplicationConfig
import io.karpfen.server.KtorServer
import io.karpfen.websocket.WebSocketManager

fun main() {
    // Load configuration from file
    val config = ApplicationConfig.loadFromFile("application.conf")

    println("[Main] Configuration loaded:")
    println("[Main]   Server: ${config.server.host}:${config.server.port}")
    println("[Main]   WebSocket enabled: ${config.websocket.enabled}")
    println("[Main]   Log level: ${config.logging.level}")

    // Initialize components
    val webSocketManager = WebSocketManager()
    val server = KtorServer(
        port = config.server.port,
        host = config.server.host,
        webSocketManager = webSocketManager
    )

    // Start WebSocket manager in its own thread (if enabled)
    if (config.websocket.enabled) {
        webSocketManager.start()
    }

    // Start HTTP server
    server.start()

    println("[Main] Server is running. Press Ctrl+C to stop.")

    // TODO: Use this thread later to control the execution engine
    // nextMessage() is then only be called as a get next event callback from the execution engine, not in a loop like this
    Thread {
        try {
            while (true) {
                val message = webSocketManager.nextMessage(timeoutMs = config.websocket.queueTimeoutMs)
                if (message != null) {
                    println("[Main] Processing message from queue: env=${message.environmentKey}, type=${message.messageType}")
                    // TODO: Process message accordingly
                }
            }
        } catch (e: Exception) {
            println("[Main] Error processing queue: ${e.message}")
        }
    }.apply {
        isDaemon = true
        start()
    }

    // Keep main thread alive
    Runtime.getRuntime().addShutdownHook(Thread {
        println("[Main] Shutting down...")
        webSocketManager.stop()
        server.stop()
    })

    Thread.sleep(Long.MAX_VALUE)
}