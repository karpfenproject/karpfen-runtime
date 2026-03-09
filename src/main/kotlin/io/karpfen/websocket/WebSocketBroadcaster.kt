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
package io.karpfen.websocket

import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking

/**
 * Handles asynchronous broadcasting of messages to WebSocket clients.
 * Runs in a separate thread and sends queued messages to connected clients.
 */
class WebSocketBroadcaster(
    private val sessionManager: ClientSessionManager
) : Runnable {
    private var isRunning = false
    private lateinit var broadcasterThread: Thread

    // Track when a client was first seen without an active session (clientId:envKey -> timestamp)
    private val missingSessionTimestamps = mutableMapOf<String, Long>()
    private val clientTimeoutMs = 10_000L

    fun start() {
        if (isRunning) {
            println("[WebSocketBroadcaster] Already running")
            return
        }

        isRunning = true
        broadcasterThread = Thread(this, "WebSocketBroadcaster-Thread").apply {
            isDaemon = false
            start()
        }
        println("[WebSocketBroadcaster] Started")
    }

    override fun run() {
        println("[WebSocketBroadcaster] Running on thread: ${Thread.currentThread().name}")
        while (isRunning) {
            try {
                val message = sessionManager.nextOutgoingMessage(timeoutMs = 1000)
                if (message != null) {
                    handleOutgoingMessage(message)
                }
            } catch (e: Exception) {
                println("[WebSocketBroadcaster] Error: ${e.message}")
            }
        }
    }

    private fun handleOutgoingMessage(message: OutgoingMessage) {
        val clientSession = sessionManager.getSessionForClient(message.environmentKey, message.clientId)
        if (clientSession == null) {
            val key = "${message.clientId}:${message.environmentKey}"
            val now = System.currentTimeMillis()
            val firstSeen = missingSessionTimestamps.getOrPut(key) { now }

            if (now - firstSeen >= clientTimeoutMs) {
                // Auto-unsubscribe the stale client after timeout
                println("[WebSocketBroadcaster] Client ${message.clientId} timed out after ${clientTimeoutMs}ms without session — removing subscriptions")
                sessionManager.unsubscribeFromObservatory(message.environmentKey, message.clientId)
                missingSessionTimestamps.remove(key)
            }
            // Silently drop the message — no spam logging
            return
        }

        // Client is active, clear any tracked missing timestamp
        val key = "${message.clientId}:${message.environmentKey}"
        missingSessionTimestamps.remove(key)

        try {
            runBlocking {
                clientSession.session.send(message.toJson())
            }
        } catch (e: Exception) {
            println("[WebSocketBroadcaster] Failed to send message to client ${message.clientId}: ${e.message}")
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        if (::broadcasterThread.isInitialized && broadcasterThread.isAlive) {
            try {
                broadcasterThread.join(5000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        println("[WebSocketBroadcaster] Stopped")
    }
}
