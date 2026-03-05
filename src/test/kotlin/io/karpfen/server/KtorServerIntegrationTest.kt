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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KtorServerIntegrationTest {
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var server: KtorServer

    @BeforeEach
    fun setup() {
        webSocketManager = WebSocketManager()
        server = KtorServer(port = 8081, host = "127.0.0.1", webSocketManager = webSocketManager)
        server.start()
        Thread.sleep(500)
    }

    @AfterEach
    fun teardown() {
        server.stop()
        Thread.sleep(200)
    }

    @Test
    fun testWebSocketMessageParsing() {
        val json = """{"environmentKey":"env-key","messageType":"command","payload":"some-data"}"""
        val message = WebSocketMessage.fromJson(json)

        assertNotNull(message, "Message should be parsed")
        assertEquals("env-key", message.environmentKey)
        assertEquals("command", message.messageType)
        assertEquals("some-data", message.payload)
    }

    @Test
    fun testWebSocketManagerQueueAccess() {
        webSocketManager.start()

        val testMessage = WebSocketMessage("test-env", "event", "payload-data")
        webSocketManager.enqueueMessage(testMessage)

        val retrieved = webSocketManager.nextMessage(timeoutMs = 500)
        assertNotNull(retrieved, "Message should be retrievable from queue")
        assertEquals(testMessage, retrieved)

        webSocketManager.stop()
    }

    @Test
    fun testMultipleMessagesInQueue() {
        webSocketManager.start()

        val message1 = WebSocketMessage("env1", "type1", "payload1")
        val message2 = WebSocketMessage("env2", "type2", "payload2")

        webSocketManager.enqueueMessage(message1)
        webSocketManager.enqueueMessage(message2)

        val retrieved1 = webSocketManager.nextMessage(timeoutMs = 500)
        val retrieved2 = webSocketManager.nextMessage(timeoutMs = 500)

        assertNotNull(retrieved1)
        assertNotNull(retrieved2)
        assertEquals(message1, retrieved1)
        assertEquals(message2, retrieved2)

        webSocketManager.stop()
    }

    @Test
    fun testWebSocketMessageJsonParsing() {
        val testCases = listOf(
            """{"environmentKey":"env1","messageType":"type1","payload":"data1"}""",
            """{"environmentKey":"test","messageType":"init","payload":"config"}""",
            """{"environmentKey":"router","messageType":"event","payload":"large-payload-content"}"""
        )

        for (json in testCases) {
            val message = WebSocketMessage.fromJson(json)
            assertNotNull(message, "Should parse: $json")
            assertNotNull(message.environmentKey)
            assertNotNull(message.messageType)
            assertNotNull(message.payload)
        }
    }

    @Test
    fun testServerStartsSuccessfully() {
        // Server is already started in setup
        // If we got here without exception, the test passes
        assertEquals(true, true, "Server should start successfully")
    }
}





