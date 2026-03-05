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

import java.util.concurrent.LinkedBlockingQueue

/**
 * Manages WebSocket connections and message queuing.
 * Runs in a separate thread to handle incoming WebSocket messages.
 */
class WebSocketManager {
    private val messageQueue = LinkedBlockingQueue<WebSocketMessage>()
    private var isRunning = false
    private lateinit var webSocketThread: Thread

    /**
     * Returns the message queue that can be accessed by other threads.
     */
    fun getMessageQueue(): LinkedBlockingQueue<WebSocketMessage> = messageQueue

    /**
     * Starts the WebSocket manager in a new thread.
     * This method initializes the WebSocket server listening for connections.
     */
    fun start() {
        if (isRunning) {
            println("[WebSocketManager] Already running")
            return
        }

        isRunning = true
        webSocketThread = Thread({
            println("[WebSocketManager] Started on thread: ${Thread.currentThread().name}")
            while (isRunning) {
                Thread.sleep(100) // Keep thread alive
            }
        }, "WebSocketManager-Thread").apply {
            isDaemon = false
            start()
        }
    }

    /**
     * Enqueues an incoming message from a WebSocket client.
     */
    fun enqueueMessage(message: WebSocketMessage) {
        messageQueue.offer(message)
        println("[WebSocketManager] Message enqueued: environment=${message.environmentKey}, type=${message.messageType}")
    }

    /**
     * Stops the WebSocket manager.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        if (::webSocketThread.isInitialized && webSocketThread.isAlive) {
            webSocketThread.join(5000)
        }
        println("[WebSocketManager] Stopped")
    }

    /**
     * Gets the next message from the queue (blocking).
     * Returns null if the queue is empty and manager is not running.
     */
    fun nextMessage(timeoutMs: Long = 1000): WebSocketMessage? {
        return messageQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}

