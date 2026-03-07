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
import io.karpfen.websocket.ClientSessionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

/**
 * End-to-end integration tests that simulate the complete lifecycle of:
 * 1. Creating an environment via HTTP API
 * 2. Loading metamodel, model, and state machine
 * 3. Registering clients and subscriptions
 * 4. Activating and starting/stopping the environment
 * 5. Verifying observer notifications flow through the system
 *
 * These tests use the cleaning_robot example as a concrete execution case.
 */
class EndToEndIntegrationTest {

    private lateinit var sessionManager: ClientSessionManager

    // Cleaning robot example files content
    private val kmetaContent = """
        type "Vector" "A vector in 2D space" {
            prop("x", "number")
            prop("y", "number")
        }

        type "TwoDObject" "A two-dimensional object with a position and diameter" {
            prop("diameter", "number")
            has("position", "Vector")
        }

        type "Obstacle" "circular obstacle in the room" {
            has("boundingBox", "TwoDObject")
        }

        type "Wall" "An obstacle that represents a wall as an infinite line" {
            has("p", "Vector")
            has("u_vec", "Vector")
        }

        type "Robot" "A cleaning robot that can move around the room and log its actions" {
            prop("log", list("string"))
            prop("d_closest_obstacle", "number")
            prop("d_closest_wall", "number")
            has("boundingBox", "TwoDObject")
            has("direction", "Vector")
            knows("obstacles", list("Obstacle"))
            knows("walls", list("Wall"))
            knows("closest_obstacle", "Obstacle")
            knows("closest_wall", "Wall")
        }

        type "Room" "A room that contains a robot and obstacles" {
            has("robot", "Robot")
            has("obstacles", list("Obstacle"))
            has("walls", list("Wall"))
        }
    """.trimIndent()

    private val kmodelContent = """
        make object "APB 2101":"Room" {
            has("robot") -> make object "turtle":"Robot" {
                prop("d_closest_obstacle") -> "100.0"
                prop("d_closest_wall") -> "100.0"
                has("boundingBox") -> make object "turtleBoundingBox":"TwoDObject" {
                    prop("diameter") -> "0.2"
                    has("position") -> make object "turtlePosition":"Vector" {
                        prop("x") -> "5.0"
                        prop("y") -> "5.0"
                    }
                }
                has("direction") -> make object "turtleDirection":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "1.0"
                }
                knows("obstacles") -> "chair"
                knows("obstacles") -> "table"
                knows("walls") -> "wall_top"
                knows("walls") -> "wall_right"
                knows("walls") -> "wall_bottom"
                knows("walls") -> "wall_left"
                knows("closest_obstacle") -> "chair"
                knows("closest_wall") -> "wall_top"
            }
            has("obstacles") -> make object "chair":"Obstacle" {
                has("boundingBox") -> make object "chairBoundingBox":"TwoDObject" {
                    prop("diameter") -> "1.0"
                    has("position") -> make object "chairPosition":"Vector" {
                        prop("x") -> "2.0"
                        prop("y") -> "3.0"
                    }
                }
            }
            has("obstacles") -> make object "table":"Obstacle" {
                has("boundingBox") -> make object "tableBoundingBox":"TwoDObject" {
                    prop("diameter") -> "3.0"
                    has("position") -> make object "tablePosition":"Vector" {
                        prop("x") -> "5.0"
                        prop("y") -> "7.0"
                    }
                }
            }
            has("walls") -> make object "wall_top":"Wall" {
                has("p") -> make object "wallTopP":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "10.0"
                }
                has("u_vec") -> make object "wallTopUVec":"Vector" {
                    prop("x") -> "1.0"
                    prop("y") -> "0.0"
                }
            }
            has("walls") -> make object "wall_right":"Wall" {
                has("p") -> make object "wallRightP":"Vector" {
                    prop("x") -> "10.0"
                    prop("y") -> "0.0"
                }
                has("u_vec") -> make object "wallRightUVec":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "1.0"
                }
            }
            has("walls") -> make object "wall_bottom":"Wall" {
                has("p") -> make object "wallBottomP":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "0.0"
                }
                has("u_vec") -> make object "wallBottomUVec":"Vector" {
                    prop("x") -> "1.0"
                    prop("y") -> "0.0"
                }
            }
            has("walls") -> make object "wall_left":"Wall" {
                has("p") -> make object "wallLeftP":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "0.0"
                }
                has("u_vec") -> make object "wallLeftUVec":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "1.0"
                }
            }
        }
    """.trimIndent()

    // A minimal state machine for testing (does not require Python macros)
    private val minimalKstates = """
        STATEMACHINE ATTACHED TO "Robot" {
            STATES {
                INITIAL STATE "idle" {
                    ENTRY {
                        SET("d_closest_obstacle", "42.0")
                    }
                }
                STATE "moving" {
                    ENTRY {
                        SET("d_closest_wall", "7.0")
                    }
                }
            }
            TRANSITIONS {
                TRANSITION "idle" -> "moving" {
                    CONDITION {
                        EVENT("public", "start")
                    }
                }
                TRANSITION "moving" -> "idle" {
                    CONDITION {
                        EVENT("public", "stop")
                    }
                }
            }
            MACROS { }
        }
    """.trimIndent()

    @BeforeEach
    fun setup() {
        EnvironmentHandler.envs.clear()
        EnvironmentHandler.activeEnvs.clear()
        EnvironmentHandler.executionThreads.clear()
        sessionManager = EnvironmentHandler.clientSessionManager
    }

    @AfterEach
    fun teardown() {
        // Stop all running threads
        EnvironmentHandler.executionThreads.forEach { (key, thread) ->
            try { thread.stop() } catch (_: Exception) {}
        }
        EnvironmentHandler.envs.clear()
        EnvironmentHandler.activeEnvs.clear()
        EnvironmentHandler.executionThreads.clear()
    }

    // ========== Environment Setup Flow ==========

    @Test
    fun `complete environment setup flow with cleaning robot example`() {
        // 1. Create environment
        val envKey = APIService.createEnvironment()
        assertNotNull(envKey)
        assertTrue(envKey.startsWith("env-"))

        // 2. Set metamodel
        APIService.receiveMetamodel(envKey, kmetaContent)
        assertNotNull(EnvironmentHandler.getEnv(envKey)!!.metamodel)

        // 3. Set model
        APIService.receiveModel(envKey, kmodelContent)
        assertNotNull(EnvironmentHandler.getEnv(envKey)!!.model)

        // 4. Set state machine
        APIService.receiveStateMachine(envKey, "turtle", minimalKstates)
        assertTrue(EnvironmentHandler.getEnv(envKey)!!.stateMachines.containsKey("turtle"))

        // 5. Configure tick delay
        APIService.updateTickDelay(envKey, 500)
        assertEquals(500, EnvironmentHandler.getEnv(envKey)!!.tickDelayMS)
    }

    @Test
    fun `model must be set after metamodel`() {
        val envKey = APIService.createEnvironment()

        // Model before metamodel should fail
        assertFailsWith<IllegalStateException> {
            APIService.receiveModel(envKey, kmodelContent)
        }
    }

    @Test
    fun `cannot modify active environment`() {
        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, kmetaContent)
        APIService.receiveModel(envKey, kmodelContent)
        APIService.receiveStateMachine(envKey, "turtle", minimalKstates)

        // Activate the environment
        APIService.runEnvironment(envKey)

        // Trying to modify it should fail
        assertFailsWith<IllegalStateException> {
            APIService.receiveMetamodel(envKey, kmetaContent)
        }
        assertFailsWith<IllegalStateException> {
            APIService.receiveModel(envKey, kmodelContent)
        }
        assertFailsWith<IllegalStateException> {
            APIService.updateTickDelay(envKey, 100)
        }
    }

    @Test
    fun `cannot run environment without model and metamodel`() {
        val envKey = APIService.createEnvironment()

        assertFailsWith<IllegalStateException> {
            APIService.runEnvironment(envKey)
        }

        APIService.receiveMetamodel(envKey, kmetaContent)

        assertFailsWith<IllegalStateException> {
            APIService.runEnvironment(envKey)
        }
    }

    @Test
    fun `run and start environment creates execution thread`() {
        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, kmetaContent)
        APIService.receiveModel(envKey, kmodelContent)
        APIService.receiveStateMachine(envKey, "turtle", minimalKstates)

        APIService.runEnvironment(envKey)
        assertTrue(EnvironmentHandler.isActiveEnv(envKey))
        assertNotNull(EnvironmentHandler.executionThreads[envKey])

        APIService.startEnvironment(envKey)
        Thread.sleep(200) // Let the thread start
        // Thread should be running now
        assertTrue(EnvironmentHandler.executionThreads.containsKey(envKey))

        APIService.stopEnvironment(envKey)
    }

    // ========== Client Registration and Session Flow ==========

    @Test
    fun `client registration produces unique access keys`() {
        val envKey = APIService.createEnvironment()

        val key1 = APIService.registerClientForWebSocket("client-A", envKey)
        val key2 = APIService.registerClientForWebSocket("client-B", envKey)

        assertNotNull(key1)
        assertNotNull(key2)
        assertTrue(key1 != key2, "Access keys must be unique")
    }

    @Test
    fun `client registration fails for nonexistent environment`() {
        assertFailsWith<IllegalArgumentException> {
            APIService.registerClientForWebSocket("client-A", "nonexistent")
        }
    }

    @Test
    fun `client websocket session lifecycle`() {
        val envKey = APIService.createEnvironment()
        val accessKey = APIService.registerClientForWebSocket("client-A", envKey)
        val mockSession = MockWebSocketSession()

        // Attach session using access key
        val attached = sessionManager.verifyAndAttachSession("client-A", envKey, accessKey, mockSession)
        assertTrue(attached)

        // Session should be retrievable
        val session = sessionManager.getSessionForClient(envKey, "client-A")
        assertNotNull(session)
        assertEquals("client-A", session.clientId)

        // Unregister
        sessionManager.unregisterClientSession(envKey, accessKey)
        assertNull(sessionManager.getSessionForClient(envKey, "client-A"))
    }

    @Test
    fun `access key cannot be reused after session attach`() {
        val envKey = APIService.createEnvironment()
        val accessKey = APIService.registerClientForWebSocket("client-A", envKey)
        val mockSession1 = MockWebSocketSession()
        val mockSession2 = MockWebSocketSession()

        // First attach should succeed
        assertTrue(sessionManager.verifyAndAttachSession("client-A", envKey, accessKey, mockSession1))

        // Second attach with same key should fail (pending registration is consumed)
        assertFalse(sessionManager.verifyAndAttachSession("client-A", envKey, accessKey, mockSession2))
    }

    // ========== Object Observation Flow ==========

    @Test
    fun `object observation notifies subscribed clients`() {
        val envKey = APIService.createEnvironment()
        APIService.registerClientForWebSocket("observer-1", envKey)

        // Subscribe to turtle object
        sessionManager.subscribeToObject(envKey, "observer-1", "turtle")

        // Trigger a change notification
        sessionManager.notifyObjectChange(envKey, "turtle", """{"__id__":"turtle","x":5}""")

        // Check the outgoing queue
        val msg = sessionManager.nextOutgoingMessage(200)
        assertNotNull(msg)
        assertEquals("observer-1", msg.clientId)
        assertEquals("objectChanged", msg.messageType)
        assertTrue(msg.payload.contains("turtle"))
    }

    @Test
    fun `multiple object subscriptions work independently`() {
        val envKey = APIService.createEnvironment()

        sessionManager.subscribeToObject(envKey, "client-1", "turtle")
        sessionManager.subscribeToObject(envKey, "client-1", "turtlePosition")

        // Notify turtle change
        sessionManager.notifyObjectChange(envKey, "turtle", """{"__id__":"turtle"}""")
        val msg1 = sessionManager.nextOutgoingMessage(100)
        assertNotNull(msg1)
        assertEquals("objectChanged", msg1.messageType)

        // Notify turtlePosition change
        sessionManager.notifyObjectChange(envKey, "turtlePosition", """{"__id__":"turtlePosition"}""")
        val msg2 = sessionManager.nextOutgoingMessage(100)
        assertNotNull(msg2)
        assertEquals("objectChanged", msg2.messageType)

        // No more messages
        assertNull(sessionManager.nextOutgoingMessage(50))
    }

    @Test
    fun `object subscription via API service stores observation`() {
        val envKey = APIService.createEnvironment()

        APIService.addObjectObservation(envKey, "client-1", "turtle")
        APIService.addObjectObservation(envKey, "client-2", "turtlePosition")

        val env = EnvironmentHandler.getEnv(envKey)!!
        assertEquals(2, env.objectObservations.size)
        assertEquals("client-1", env.objectObservations[0].observingClientId)
        assertEquals("turtle", env.objectObservations[0].observedObjectId)
    }

    // ========== Domain Listener Flow ==========

    @Test
    fun `domain listener receives events`() {
        val envKey = APIService.createEnvironment()

        sessionManager.subscribeToDomain(envKey, "client-1", "public")
        sessionManager.notifyDomainEvent(envKey, "public", """{"event":"start"}""")

        val msg = sessionManager.nextOutgoingMessage(100)
        assertNotNull(msg)
        assertEquals("domainEvent", msg.messageType)
        assertEquals("client-1", msg.clientId)
    }

    @Test
    fun `domain listener via API service stores listener`() {
        val envKey = APIService.createEnvironment()

        APIService.addDomainListener(envKey, "client-1", "public")
        APIService.addDomainListener(envKey, "client-1", "local")

        val env = EnvironmentHandler.getEnv(envKey)!!
        assertEquals(2, env.domainListeners.size)
    }

    // ========== Event TTL Configuration ==========

    @Test
    fun `event TTL is configurable per environment`() {
        val envKey = APIService.createEnvironment()

        APIService.updateEventTtl(envKey, 5000L)
        assertEquals(5000L, EnvironmentHandler.getEnv(envKey)!!.eventTtlMs)
    }

    // ========== Full Setup with Engine Integration ==========

    @Test
    fun `full environment with observer integration triggers change publisher`() {
        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, kmetaContent)
        APIService.receiveModel(envKey, kmodelContent)
        APIService.receiveStateMachine(envKey, "turtle", minimalKstates)
        APIService.updateTickDelay(envKey, 100)

        // Register client and subscribe to turtle object BEFORE activation
        APIService.registerClientForWebSocket("viewer-1", envKey)
        APIService.addObjectObservation(envKey, "viewer-1", "turtle")
        sessionManager.subscribeToObject(envKey, "viewer-1", "turtle")

        // Activate and start
        APIService.runEnvironment(envKey)

        // The EnvironmentThread.setup() should have registered the ModelChangePublisher.
        // The Engine should have run the initial state's onEntry (SET d_closest_obstacle = 42.0)
        // which should trigger a model change notification.
        APIService.startEnvironment(envKey)

        // Wait for the engine to execute at least one tick
        Thread.sleep(500)

        // Check if a change notification was queued
        val msg = sessionManager.nextOutgoingMessage(500)
        assertNotNull(msg, "Model change notification should have been queued after onEntry execution")
        assertEquals("objectChanged", msg.messageType)
        assertTrue(msg.payload.contains("turtle"))

        APIService.stopEnvironment(envKey)
    }

    // ========== WebSocket Message Parsing ==========

    @Test
    fun `WebSocketMessage parsing handles all fields`() {
        val json = """{"environmentKey":"env-123","messageType":"start","payload":"go"}"""
        val msg = io.karpfen.websocket.WebSocketMessage.fromJson(json)
        assertNotNull(msg)
        assertEquals("env-123", msg.environmentKey)
        assertEquals("start", msg.messageType)
        assertEquals("go", msg.payload)
    }

    @Test
    fun `WebSocketMessage parsing handles empty payload`() {
        val json = """{"environmentKey":"env-123","messageType":"ping","payload":""}"""
        val msg = io.karpfen.websocket.WebSocketMessage.fromJson(json)
        assertNotNull(msg)
        assertEquals("", msg.payload)
    }

    @Test
    fun `WebSocketMessage parsing handles missing payload gracefully`() {
        val json = """{"environmentKey":"env-123","messageType":"ping"}"""
        val msg = io.karpfen.websocket.WebSocketMessage.fromJson(json)
        assertNotNull(msg)
        assertEquals("", msg.payload)
    }

    @Test
    fun `WebSocketMessage parsing rejects garbage input`() {
        assertNull(io.karpfen.websocket.WebSocketMessage.fromJson("not json"))
        assertNull(io.karpfen.websocket.WebSocketMessage.fromJson(""))
        assertNull(io.karpfen.websocket.WebSocketMessage.fromJson("{}"))
    }

    // ========== Error Handling ==========

    @Test
    fun `start environment without run fails`() {
        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, kmetaContent)
        APIService.receiveModel(envKey, kmodelContent)
        APIService.receiveStateMachine(envKey, "turtle", minimalKstates)

        // startEnvironment without runEnvironment should fail
        assertFailsWith<Exception> {
            APIService.startEnvironment(envKey)
        }
    }

    @Test
    fun `stop nonexistent environment thread fails`() {
        val envKey = APIService.createEnvironment()
        assertFailsWith<Exception> {
            APIService.stopEnvironment(envKey)
        }
    }

    @Test
    fun `multiple state machines can be attached to same environment`() {
        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, kmetaContent)
        APIService.receiveModel(envKey, kmodelContent)

        // Attach to different model elements
        APIService.receiveStateMachine(envKey, "turtle", minimalKstates)
        APIService.receiveStateMachine(envKey, "chair", minimalKstates)

        val env = EnvironmentHandler.getEnv(envKey)!!
        assertEquals(2, env.stateMachines.size)
        assertTrue(env.stateMachines.containsKey("turtle"))
        assertTrue(env.stateMachines.containsKey("chair"))
    }

    // ========== OutgoingMessage JSON ==========

    @Test
    fun `OutgoingMessage toJson produces valid json with nested payload`() {
        val msg = io.karpfen.websocket.OutgoingMessage(
            environmentKey = "env-1",
            clientId = "client-1",
            messageType = "objectChanged",
            payload = """{"objectId":"turtle","value":{"x":5.0}}"""
        )
        val json = msg.toJson()
        // The payload should be embedded as raw JSON, not double-escaped
        assertTrue(json.contains(""""messageType":"objectChanged""""))
        assertTrue(json.contains(""""objectId":"turtle""""))
        // Validate it can be parsed back
        val parsed = org.json.JSONObject(json)
        assertEquals("env-1", parsed.getString("environmentKey"))
        assertEquals("objectChanged", parsed.getString("messageType"))
        val payload = parsed.getJSONObject("payload")
        assertEquals("turtle", payload.getString("objectId"))
    }
}

