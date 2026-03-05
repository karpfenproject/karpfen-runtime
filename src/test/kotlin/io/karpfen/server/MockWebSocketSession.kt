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

import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Mock WebSocket session for testing purposes.
 * Allows capturing sent messages without a real WebSocket connection.
 */
class MockWebSocketSession : WebSocketSession {
    private val sentMessages = mutableListOf<String>()
    private val frameChannel = Channel<Frame>()
    private val outgoingChannel = Channel<Frame>()

    override val incoming: ReceiveChannel<Frame>
        get() = frameChannel

    override val outgoing: SendChannel<Frame>
        get() = outgoingChannel

    override suspend fun send(frame: Frame) {
        if (frame is Frame.Text) {
            sentMessages.add(frame.data.decodeToString())
        }
    }

    override suspend fun flush() {
        // No-op for mock
    }

    override fun terminate() {
        frameChannel.close()
        outgoingChannel.close()
    }

    override val coroutineContext: CoroutineContext
        get() = EmptyCoroutineContext

    override var masking: Boolean = false

    @Suppress("OVERRIDE_DEPRECATION")
    override var maxFrameSize: Long = Long.MAX_VALUE

    @Suppress("UNCHECKED_CAST")
    override val extensions: List<WebSocketExtension<*>>
        get() = emptyList()

    // Test helpers
    fun getSentMessages(): List<String> = sentMessages.toList()

    fun clearMessages() {
        sentMessages.clear()
    }

    fun getLastMessage(): String? = sentMessages.lastOrNull()
}
