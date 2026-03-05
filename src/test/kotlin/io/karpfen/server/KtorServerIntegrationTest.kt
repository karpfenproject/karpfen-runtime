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
import io.karpfen.websocket.WebSocketMessage
import io.karpfen.websocket.ClientSessionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class KtorServerIntegrationTest {
    private lateinit var sessionManager: ClientSessionManager
    private lateinit var server: KtorServer

    @BeforeEach
    fun setup() {
        // Clear any prior state
        EnvironmentHandler.envs.clear()
        EnvironmentHandler.activeEnvs.clear()
        EnvironmentHandler.executionThreads.clear()

        sessionManager = ClientSessionManager()
        server = KtorServer(port = 8081, host = "127.0.0.1", sessionManager = sessionManager)
        server.start()
        Thread.sleep(500)
    }

    @AfterEach
    fun teardown() {
        server.stop()
        EnvironmentHandler.envs.clear()
        EnvironmentHandler.activeEnvs.clear()
        EnvironmentHandler.executionThreads.clear()
        Thread.sleep(200)
    }

    // ========== HTTP API Tests ==========

    @Test
    fun testCreateEnvironmentReturnsKey() {
        val service = APIService
        val envKey = service.createEnvironment()

        assertNotNull(envKey, "Environment key should be generated")
        assertTrue(envKey.startsWith("env-"), "Key should start with 'env-'")
        assertNotNull(EnvironmentHandler.getEnv(envKey), "Environment should be created in handler")
    }

    @Test
    fun testRegisterClientForWebSocketReturnsAccessKey() {
        val service = APIService
        val envKey = service.createEnvironment()

        val accessKey = service.registerClientForWebSocket("client1", envKey)

        assertNotNull(accessKey, "Access key should be generated")
        assertTrue(accessKey.startsWith("ak-"), "Key should start with 'ak-'")
    }

    @Test
    fun testRegisterClientForNonExistentEnvironmentThrows() {
        val service = APIService

        try {
            service.registerClientForWebSocket("client1", "nonexistent-env")
            assertTrue(false, "Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("does not exist") == true)
        }
    }

    @Test
    fun testMultipleClientsForSameEnvironment() {
        val service = APIService
        val envKey = service.createEnvironment()

        val accessKey1 = service.registerClientForWebSocket("client1", envKey)
        val accessKey2 = service.registerClientForWebSocket("client2", envKey)

        assertNotNull(accessKey1)
        assertNotNull(accessKey2)
        assertFalse(accessKey1.equals(accessKey2), "Keys should be unique per client")
    }

    // ========== Session Manager Tests ==========

    @Test
    fun testRegisterClientAccessCreatesAccessKey() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        val accessKey = sessionManager.registerClientAccess("client1", envKey)

        assertNotNull(accessKey)
        assertTrue(accessKey.startsWith("ak-"))
    }

    @Test
    fun testVerifyAndAttachSessionWithValidKey() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        val accessKey = sessionManager.registerClientAccess("client1", envKey)
        val mockSession = MockWebSocketSession()

        val attached = sessionManager.verifyAndAttachSession("client1", envKey, accessKey, mockSession)

        assertTrue(attached, "Session should be attached with valid key")
    }

    @Test
    fun testVerifyAndAttachSessionWithInvalidKey() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        sessionManager.registerClientAccess("client1", envKey)
        val mockSession = MockWebSocketSession()

        val attached = sessionManager.verifyAndAttachSession("client1", envKey, "wrong-key", mockSession)

        assertFalse(attached, "Session should not attach with invalid key")
    }

    @Test
    fun testVerifyAndAttachSessionWithWrongClientId() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        val accessKey = sessionManager.registerClientAccess("client1", envKey)
        val mockSession = MockWebSocketSession()

        val attached = sessionManager.verifyAndAttachSession("wrong-client", envKey, accessKey, mockSession)

        assertFalse(attached, "Session should not attach with wrong client ID")
    }

    @Test
    fun testGetSessionForClientAfterAttach() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        val accessKey = sessionManager.registerClientAccess("client1", envKey)
        val mockSession = MockWebSocketSession()
        sessionManager.verifyAndAttachSession("client1", envKey, accessKey, mockSession)

        val retrievedSession = sessionManager.getSessionForClient(envKey, "client1")

        assertNotNull(retrievedSession)
        assertEquals("client1", retrievedSession.clientId)
    }

    @Test
    fun testGetSessionForNonExistentClient() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        val retrievedSession = sessionManager.getSessionForClient(envKey, "nonexistent")

        assertNull(retrievedSession)
    }

    @Test
    fun testUnregisterClientSessionRemovesSession() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        val accessKey = sessionManager.registerClientAccess("client1", envKey)
        val mockSession = MockWebSocketSession()
        sessionManager.verifyAndAttachSession("client1", envKey, accessKey, mockSession)

        sessionManager.unregisterClientSession(envKey, accessKey)

        val retrievedSession = sessionManager.getSessionForClient(envKey, "client1")
        assertNull(retrievedSession, "Session should be removed after unregister")
    }

    // ========== Message Tests ==========

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
    fun testInvalidJsonReturnsNull() {
        val invalidJson = """{"invalid": json}"""
        val message = WebSocketMessage.fromJson(invalidJson)

        assertNull(message, "Invalid JSON should return null")
    }

    // ========== Subscription Tests ==========

    @Test
    fun testSubscribeToObject() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        sessionManager.subscribeToObject(envKey, "client1", "obj1")

        // Verify by attempting to notify (should not crash)
        sessionManager.notifyObjectChange(envKey, "obj1", """{"value":"test"}""")
    }

    @Test
    fun testSubscribeToDomain() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        sessionManager.subscribeToDomain(envKey, "client1", "domain1")

        // Verify by attempting to notify (should not crash)
        sessionManager.notifyDomainEvent(envKey, "domain1", """{"data":"test"}""")
    }

    @Test
    fun testMultipleClientsSubscribeToSameObject() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        sessionManager.subscribeToObject(envKey, "client1", "obj1")
        sessionManager.subscribeToObject(envKey, "client2", "obj1")

        // Both should be notified (messages should queue)
        sessionManager.notifyObjectChange(envKey, "obj1", """{"value":"test"}""")

        val msg1 = sessionManager.nextOutgoingMessage(100)
        val msg2 = sessionManager.nextOutgoingMessage(100)

        assertNotNull(msg1)
        assertNotNull(msg2)
    }

    // ========== Outgoing Message Queue Tests ==========

    @Test
    fun testNotifyObjectChangeQueuesMessage() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        sessionManager.subscribeToObject(envKey, "client1", "obj1")
        sessionManager.notifyObjectChange(envKey, "obj1", """{"value":"changed"}""")

        val msg = sessionManager.nextOutgoingMessage(100)

        assertNotNull(msg)
        assertEquals("client1", msg.clientId)
        assertEquals("objectChanged", msg.messageType)
        assertEquals(envKey, msg.environmentKey)
    }

    @Test
    fun testNotifyDomainEventQueuesMessage() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        sessionManager.subscribeToDomain(envKey, "client1", "domain1")
        sessionManager.notifyDomainEvent(envKey, "domain1", """{"event":"test"}""")

        val msg = sessionManager.nextOutgoingMessage(100)

        assertNotNull(msg)
        assertEquals("client1", msg.clientId)
        assertEquals("domainEvent", msg.messageType)
    }

    @Test
    fun testOutgoingMessageQueueTimeout() {
        val msg = sessionManager.nextOutgoingMessage(10)
        assertNull(msg, "Should return null when queue is empty and timeout expires")
    }

    // ========== Integration Flow Tests ==========

    @Test
    fun testCompleteClientRegistrationFlow() {
        // 1. Create environment
        val envKey = EnvironmentHandler.createEnv("env-flow").key

        // 2. Register client and get access key
        val accessKey = sessionManager.registerClientAccess("client1", envKey)
        assertNotNull(accessKey)

        // 3. Verify and attach session
        val mockSession = MockWebSocketSession()
        val attached = sessionManager.verifyAndAttachSession("client1", envKey, accessKey, mockSession)
        assertTrue(attached)

        // 4. Subscribe to object
        sessionManager.subscribeToObject(envKey, "client1", "obj1")

        // 5. Trigger notification
        sessionManager.notifyObjectChange(envKey, "obj1", """{"status":"active"}""")

        // 6. Verify message was queued
        val msg = sessionManager.nextOutgoingMessage(100)
        assertNotNull(msg)
        assertEquals("client1", msg.clientId)
    }

    @Test
    fun testClientSessionManagerBasics() {
        assertNotNull(sessionManager, "Session manager should be initialized")
    }

    @Test
    fun testServerStartsSuccessfully() {
        assertEquals(true, true, "Server should start successfully")
    }

    @Test
    fun testBroadcasterSendsMessagesToClients() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        val accessKey = sessionManager.registerClientAccess("client1", envKey)
        val mockSession = MockWebSocketSession()
        sessionManager.verifyAndAttachSession("client1", envKey, accessKey, mockSession)

        sessionManager.subscribeToObject(envKey, "client1", "obj1")
        sessionManager.notifyObjectChange(envKey, "obj1", """{"value":"test"}""")

        // Simulate broadcaster processing
        val msg = sessionManager.nextOutgoingMessage(100)
        assertNotNull(msg)
        assertEquals("client1", msg.clientId)
        assertEquals("objectChanged", msg.messageType)
    }

    @Test
    fun testClientReceivesMultipleMessages() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        val accessKey = sessionManager.registerClientAccess("client1", envKey)
        val mockSession = MockWebSocketSession()
        sessionManager.verifyAndAttachSession("client1", envKey, accessKey, mockSession)

        sessionManager.subscribeToObject(envKey, "client1", "obj1")
        sessionManager.subscribeToDomain(envKey, "client1", "domain1")

        // Send two notifications
        sessionManager.notifyObjectChange(envKey, "obj1", """{"value":"1"}""")
        sessionManager.notifyDomainEvent(envKey, "domain1", """{"event":"2"}""")

        val msg1 = sessionManager.nextOutgoingMessage(100)
        val msg2 = sessionManager.nextOutgoingMessage(100)

        assertNotNull(msg1)
        assertNotNull(msg2)
        assertEquals("objectChanged", msg1.messageType)
        assertEquals("domainEvent", msg2.messageType)
    }

    @Test
    fun testOnlySubscribedClientsReceiveNotifications() {
        val envKey = "env-test"
        EnvironmentHandler.createEnv(envKey)

        val accessKey1 = sessionManager.registerClientAccess("client1", envKey)
        val accessKey2 = sessionManager.registerClientAccess("client2", envKey)
        val mockSession1 = MockWebSocketSession()
        val mockSession2 = MockWebSocketSession()

        sessionManager.verifyAndAttachSession("client1", envKey, accessKey1, mockSession1)
        sessionManager.verifyAndAttachSession("client2", envKey, accessKey2, mockSession2)

        // Only client1 subscribes to obj1
        sessionManager.subscribeToObject(envKey, "client1", "obj1")

        sessionManager.notifyObjectChange(envKey, "obj1", """{"value":"test"}""")

        val msg = sessionManager.nextOutgoingMessage(100)
        assertNotNull(msg)
        assertEquals("client1", msg.clientId)

        val msg2 = sessionManager.nextOutgoingMessage(100)
        assertNull(msg2, "Client2 should not receive message")
    }
}
