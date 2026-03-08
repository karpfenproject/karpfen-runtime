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
import io.karpfen.env.EnvironmentHandler
import io.karpfen.websocket.WebSocketBroadcaster

fun main() {
    val config = ApplicationConfig.loadFromFile("application.conf")

    println("[Main] Configuration loaded:")
    println("[Main]   Server: ${config.server.host}:${config.server.port}")
    println("[Main]   WebSocket enabled: ${config.websocket.enabled}")
    println("[Main]   Log level: ${config.logging.level}")
    println("[Main]   Engine tracing: ${config.engineTracing.enabled}")
    println("[Main]   Default tick delay: ${config.engine.defaultTickDelayMs}ms")

    // Apply engine tracing configuration
    if (config.engineTracing.enabled) {
        EnvironmentHandler.traceLogDirectory = config.engineTracing.logDirectory
        EnvironmentHandler.traceConsoleOutput = config.engineTracing.consoleOutput
    }

    // Apply engine configuration
    EnvironmentHandler.defaultTickDelayMs = config.engine.defaultTickDelayMs

    val sessionManager = EnvironmentHandler.clientSessionManager
    val server = KtorServer(
        port = config.server.port,
        host = config.server.host,
        sessionManager = sessionManager
    )
    if (config.websocket.enabled) {
        val broadcaster = WebSocketBroadcaster(sessionManager)
        broadcaster.start()
    }
    server.start()

    println("[Main] Server is running. Press Ctrl+C to stop.")

    Runtime.getRuntime().addShutdownHook(Thread {
        println("[Main] Shutting down...")
        server.stop()
    })
    Thread.sleep(Long.MAX_VALUE)
}

