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
import io.karpfen.env.RegistryEnvironmentLookup
import io.karpfen.fixtures.Eventually
import io.karpfen.fixtures.RuntimeRegistry
import io.karpfen.observation.ModelSnapshotService
import io.karpfen.observation.ModelSubscriptionService
import io.karpfen.observation.SnapshotMirror
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the server side of state machine management: the two forms of `setStateMachine`, the refusals of uploads and
 * of `runEnvironment`, the routes that describe what an environment holds, and what clients learn through pushes and
 * snapshots when instances appear and leave. The environments use a room with a lamp, a bulb and spare bulbs whose
 * type behaves after `glowing`, and literal actions only.
 */
class StateMachineManagementTest {

    private val snapshots = ModelSnapshotService(RegistryEnvironmentLookup, ModelSnapshotService.DEFAULT_TICK_WAIT_MS)

    private val subscriptions =
        ModelSubscriptionService(RegistryEnvironmentLookup, EnvironmentHandler.clientSessionManager)

    @BeforeEach
    fun resetBefore() {
        RuntimeRegistry.reset()
    }

    @AfterEach
    fun resetAfter() {
        RuntimeRegistry.reset()
    }

    @Test
    fun `an upload without attachedTo keeps the attachable state machine under its name`() {
        val envKey = configured()
        APIService.receiveStateMachine(envKey, null, BLINKING)
        APIService.receiveStateMachine(envKey, null, GLOWING.replace("\"lit\" { ENTRY", "\"lit\" { DO"))

        val env = assertNotNull(EnvironmentHandler.getEnv(envKey))
        assertEquals(listOf("glowing", "blinking"), env.stateMachineDefinitions.keys.toList())
        assertTrue(env.stateMachineDefinitionSources.getValue("glowing").contains("DO"))
        assertEquals(
            """{"attached":["room"],"definitions":[{"name":"glowing","types":["Bulb"]},{"name":"blinking","types":["Bulb","Lamp"]}],"instances":[]}""",
            APIService.describeStateMachines(envKey).toJson()
        )
    }

    @Test
    fun `an upload of the wrong form is refused with the form it needs`() {
        val envKey = configured()

        val attachable = assertFailsWith<IllegalArgumentException> {
            APIService.receiveStateMachine(envKey, "lamp", BLINKING)
        }
        val attached = assertFailsWith<IllegalArgumentException> {
            APIService.receiveStateMachine(envKey, null, IDLE_ROOM)
        }
        val broken = assertFailsWith<UploadRejectedException> {
            APIService.receiveStateMachine(envKey, null, BLINKING.replace("\"Bulb\", \"Lamp\"", "\"Bulb\", \"Bulb\""))
        }

        assertEquals(
            "The state machine for 'lamp' must have the attached form STATEMACHINE ATTACHED TO \"Type\", but it is " +
                "the attachable state machine 'blinking'; upload an attachable state machine without attachedTo",
            attachable.message
        )
        assertEquals(
            "A state machine without attachedTo must have the attachable form STATEMACHINE \"name\" ATTACHABLE TO " +
                "\"Type\" or the fragment form STATEMACHINE \"name\" FRAGMENT; give attachedTo for a state machine " +
                "ATTACHED TO a type",
            attached.message
        )
        assertTrue(broken.message!!.startsWith("Upload of the attachable state machine rejected: 1 error\n"), broken.message)
        assertTrue(broken.message!!.endsWith("(kstates.repeated-attachable-type)"), broken.message)
        val env = assertNotNull(EnvironmentHandler.getEnv(envKey))
        assertEquals(setOf("glowing"), env.stateMachineDefinitions.keys)
        assertEquals(setOf("room"), env.stateMachines.keys)
    }

    @Test
    fun `runEnvironment refuses types that behave after a missing or unfitting definition`() {
        val missing = configured(definitions = listOf(BLINKING))
        val unfitting = configured(definitions = listOf(GLOWING.replace("ATTACHABLE TO \"Bulb\"", "ATTACHABLE TO \"Lamp\"")))

        val missingRefusal = assertFailsWith<IllegalStateException> { APIService.runEnvironment(missing) }
        val unfittingRefusal = assertFailsWith<IllegalStateException> { APIService.runEnvironment(unfitting) }

        assertEquals(
            "Environment with key $missing has types that behave after state machines it does not hold: " +
                "'Bulb' behaves after 'glowing'",
            missingRefusal.message
        )
        assertEquals(
            "Environment with key $unfitting has types that behave after state machines not attachable to them: " +
                "'Bulb' behaves after 'glowing', which is attachable to 'Lamp'",
            unfittingRefusal.message
        )
        APIService.receiveStateMachine(missing, null, GLOWING)
        APIService.runEnvironment(missing)
    }

    @Test
    fun `clients learn which instances appear and leave, and a mirror of pushes matches the snapshot`() {
        val envKey = configured(room = DROPPING_ROOM)
        APIService.runEnvironment(envKey)
        val accessKey = APIService.registerClientForWebSocket(CLIENT, envKey)
        assertTrue(
            EnvironmentHandler.clientSessionManager.verifyAndAttachSession(CLIENT, envKey, accessKey, MockWebSocketSession())
        )
        subscriptions.subscribe(envKey, CLIENT)
        val before = snapshots.snapshot(envKey)

        APIService.startEnvironment(envKey)
        Eventually.awaitUntil("the lamp and its bulb left") {
            snapshots.snapshot(envKey).stateMachines.keys == setOf("room", "b2", "b3")
        }
        val after = snapshots.snapshot(envKey)
        val pushes = drainPushes(envKey)

        val lifecycle = pushes.filter { it.first.startsWith("stateMachine") }
            .map { "${it.first}:${it.second.getString("modelElementId")}:${it.second.opt("stateMachine")}" }
        assertEquals(
            listOf(
                "stateMachineAttached:room:null",
                "stateMachineAttached:b1:glowing",
                "stateMachineAttached:b2:glowing",
                "stateMachineAttached:b3:glowing",
                "stateMachineDetached:b1:glowing"
            ),
            lifecycle
        )
        assertEquals(linkedMapOf("room" to null, "b2" to "glowing", "b3" to "glowing"), after.stateMachines)
        assertEquals(setOf("room", "b2", "b3"), after.activeStates.keys)
        assertTrue(after.toJson().contains("\"stateMachines\":{\"room\":null,\"b2\":\"glowing\",\"b3\":\"glowing\"}"))
        val mirror = SnapshotMirror(before)
        pushes.filter { it.second.optLong("revision") in (before.revision + 1)..after.revision }
            .forEach { (type, payload) -> mirror.apply(type, payload) }
        assertTrue(mirror.matches(after), "the mirror at ${mirror.revision} differs from the snapshot at ${after.revision}")
    }

    @Test
    fun `the routes describe the instances and their sources, and setActiveState reaches a behaves after instance`() {
        val envKey = configured(room = DROPPING_ROOM, definitions = listOf(GLOWING, BLINKING))
        APIService.runEnvironment(envKey)
        assertEquals(listOf("room", "b1", "b2", "b3"), APIService.describeStateMachines(envKey).instances.map { it.first })

        APIService.startEnvironment(envKey)
        Eventually.awaitUntil("b1 left") { "b1" !in snapshotKeys(envKey) }

        assertEquals(GLOWING, APIService.getStateMachineSource(envKey, "b2", null))
        assertEquals(DROPPING_ROOM, APIService.getStateMachineSource(envKey, "room", null))
        assertEquals(BLINKING, APIService.getStateMachineSource(envKey, null, "blinking"))
        assertEquals(
            "No attachable state machine named 'nothing' in environment $envKey",
            assertFailsWith<IllegalArgumentException> { APIService.getStateMachineSource(envKey, null, "nothing") }.message
        )
        assertEquals(
            "No state machine for model element 'b1' in environment $envKey",
            assertFailsWith<IllegalArgumentException> { APIService.forceActiveState(envKey, "b1", "dark") }.message
        )
        assertEquals(
            "Unknown state 'off' for model element 'b2'",
            assertFailsWith<IllegalArgumentException> { APIService.forceActiveState(envKey, "b2", "off") }.message
        )
        APIService.forceActiveState(envKey, "b2", "dark")
        assertEquals(listOf("room", "b2", "b3"), APIService.listActiveEnvironments().single()["modelElements"])
    }

    @Test
    fun `the HTTP routes take both forms of setStateMachine and describe the holdings`() = testApplication {
        application { HTTPRoutes.configure(this) }
        val envKey = configured(definitions = emptyList())

        val definition = client.put("/setStateMachine?envKey=$envKey") { setBody(GLOWING) }
        val wrongForm = client.put("/setStateMachine?envKey=$envKey&attachedTo=b1") { setBody(GLOWING) }
        val holdings = client.get("/observatory/stateMachines?envKey=$envKey")
        val source = client.get("/observatory/statemachine?envKey=$envKey&name=glowing")

        assertEquals(HttpStatusCode.OK, definition.status)
        assertEquals(HttpStatusCode.BadRequest, wrongForm.status)
        assertTrue(wrongForm.bodyAsText().startsWith("The state machine for 'b1' must have the attached form"))
        assertEquals(HttpStatusCode.OK, holdings.status)
        assertEquals("glowing", JSONObject(holdings.bodyAsText()).getJSONArray("definitions").getJSONObject(0).getString("name"))
        assertEquals(GLOWING, source.bodyAsText())
    }

    /** The keys of the instances in the snapshot of [envKey]. */
    private fun snapshotKeys(envKey: String): Set<String> = snapshots.snapshot(envKey).stateMachines.keys

    /** Takes every queued message and keeps the pushes for [CLIENT] in [envKey] as message type and payload. */
    private fun drainPushes(envKey: String): List<Pair<String, JSONObject>> =
        generateSequence { EnvironmentHandler.clientSessionManager.nextOutgoingMessage(0) }
            .filter { it.environmentKey == envKey && it.clientId == CLIENT }
            .map { it.messageType to JSONObject(it.payload) }
            .toList()

    /**
     * Creates an environment with the room metamodel and model, [room] attached to `room` and the attachable
     * [definitions], with a tick delay of 5 ms.
     */
    private fun configured(room: String = IDLE_ROOM, definitions: List<String> = listOf(GLOWING)): String {
        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, METAMODEL)
        APIService.receiveModel(envKey, MODEL)
        APIService.receiveStateMachine(envKey, "room", room)
        definitions.forEach { APIService.receiveStateMachine(envKey, null, it) }
        APIService.updateTickDelay(envKey, 5)
        return envKey
    }

    private companion object {
        const val CLIENT = "karpfen-ide-attachment"

        val METAMODEL = """
            type "Room" "A room with a lamp and spare bulbs" {
                has("lamp", "Lamp")
                has("spare", "Bulb")
                has("spares", list("Bulb"))
            }
            type "Lamp" "A lamp with a bulb" {
                has("bulb", "Bulb")
            }
            type "Bulb" "A light bulb" behaves after "glowing" {
                prop("lit", "boolean")
            }
        """.trimIndent()

        val MODEL = """
            make object "room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" {
                    has("bulb") -> make object "b1":"Bulb" { prop("lit") -> "false" }
                }
                has("spare") -> make object "b2":"Bulb" { prop("lit") -> "false" }
                has("spares") -> make object "b3":"Bulb" { prop("lit") -> "false" }
            }
        """.trimIndent()

        val GLOWING = """
            STATEMACHINE "glowing" ATTACHABLE TO "Bulb" {
                STATES {
                    INITIAL STATE "dark" { ENTRY { SET("lit", "false") } }
                    STATE "lit" { ENTRY { SET("lit", "true") } }
                }
                TRANSITIONS {
                    TRANSITION "dark" -> "lit" { }
                }
            }
        """.trimIndent()

        val BLINKING = """
            STATEMACHINE "blinking" ATTACHABLE TO "Bulb", "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    STATE "on" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "on" { }
                    TRANSITION "on" -> "off" { }
                }
            }
        """.trimIndent()

        val IDLE_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES { INITIAL STATE "idle" { } }
                TRANSITIONS { }
            }
        """.trimIndent()

        val DROPPING_ROOM = """
            STATEMACHINE ATTACHED TO "Room" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "dropped" { ENTRY { DROPOBJ("lamp") } }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "dropped" { }
                }
            }
        """.trimIndent()
    }
}
