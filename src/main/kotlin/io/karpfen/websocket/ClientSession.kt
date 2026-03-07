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

import io.ktor.websocket.WebSocketSession

/**
 * Represents a connected WebSocket client session.
 *
 * @property clientId Unique identifier for this client
 * @property environmentKey The environment this client is connected to
 * @property accessKey Authentication key for this session
 * @property session The actual WebSocket session for sending messages
 */
data class ClientSession(
    val clientId: String,
    val environmentKey: String,
    val accessKey: String,
    val session: WebSocketSession
)

/**
 * Represents a message to be sent to WebSocket clients.
 * This could be data change notifications.
 *
 * @property clientId The target client
 * @property messageType Type of the message (e.g., "dataChange", "event")
 * @property payload The actual message content
 */
data class OutgoingMessage(
    val environmentKey: String,
    val clientId: String,
    val messageType: String,
    val payload: String
) {
    fun toJson(): String {
        val escapedEnvKey = environmentKey.replace("\"", "\\\"")
        val escapedClientId = clientId.replace("\"", "\\\"")
        val escapedMsgType = messageType.replace("\"", "\\\"")
        return """{"environmentKey":"$escapedEnvKey","clientId":"$escapedClientId","messageType":"$escapedMsgType","payload":$payload}"""
    }
}
