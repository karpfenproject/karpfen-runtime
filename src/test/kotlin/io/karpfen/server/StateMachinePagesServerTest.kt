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
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.observation.ModelSnapshotService
import io.karpfen.observation.ModelSubscriptionService
import io.ktor.client.request.post
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the server side of state machine pages: the upload of fragments, the one name space of fragments and
 * attachable state machines, the refusal of `runEnvironment` while a state machine does not flatten and the run after
 * the missing upload, and the qualified names that clients see in `stateChange`, in the snapshot and in
 * `setActiveState`. The state machines use literal actions only.
 */
class StateMachinePagesServerTest {

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
    fun `an upload without attachedTo keeps a fragment under its name, attachedTo and errors refuse it`() {
        val envKey = configured()

        APIService.receiveStateMachine(envKey, null, BULB_TEST)
        val attached = assertFailsWith<IllegalArgumentException> {
            APIService.receiveStateMachine(envKey, LAMP, BULB_TEST)
        }
        val broken = assertFailsWith<UploadRejectedException> {
            APIService.receiveStateMachine(envKey, null, BULB_TEST.replace("FINAL STATE", "STATE"))
        }

        val env = assertNotNull(EnvironmentHandler.getEnv(envKey))
        assertEquals(listOf("bulb test"), env.stateMachineFragments.keys.toList())
        assertEquals(BULB_TEST, APIService.getStateMachineSource(envKey, null, "bulb test"))
        assertEquals(
            "The state machine for 'lamp' must have the attached form STATEMACHINE ATTACHED TO \"Type\", but it is " +
                "the fragment 'bulb test'; upload a fragment without attachedTo",
            attached.message
        )
        assertTrue(broken.message!!.startsWith("Upload of the fragment rejected: 1 error\n"), broken.message)
        assertTrue(broken.message!!.endsWith("(kstates.fragment-without-final-state)"), broken.message)
        assertEquals(setOf(LAMP), env.stateMachines.keys)
        assertEquals(emptySet(), env.stateMachineDefinitions.keys)
    }

    @Test
    fun `fragments and attachable state machines share one name space and the latest upload holds the name`() {
        val envKey = configured()
        val attachable = """
            STATEMACHINE "bulb test" ATTACHABLE TO "Lamp" {
                STATES { INITIAL STATE "idle" { } }
                TRANSITIONS { }
            }
        """.trimIndent()
        val env = assertNotNull(EnvironmentHandler.getEnv(envKey))

        APIService.receiveStateMachine(envKey, null, attachable)
        APIService.receiveStateMachine(envKey, null, BULB_TEST)
        assertEquals(setOf("bulb test"), env.stateMachineFragments.keys)
        assertEquals(emptySet(), env.stateMachineDefinitions.keys)
        assertEquals(BULB_TEST, APIService.getStateMachineSource(envKey, null, "bulb test"))

        APIService.receiveStateMachine(envKey, null, attachable)
        assertEquals(emptySet(), env.stateMachineFragments.keys)
        assertEquals(setOf("bulb test"), env.stateMachineDefinitions.keys)
        assertEquals(attachable, APIService.getStateMachineSource(envKey, null, "bulb test"))
    }

    @Test
    fun `runEnvironment refuses state machines that do not flatten and runs once the fragments are there`() {
        val envKey = configured(definitions = listOf(PAGED))

        val refusal = assertFailsWith<IllegalStateException> { APIService.runEnvironment(envKey) }

        assertEquals(
            "Environment with key $envKey has state machines that cannot be flattened:\n" +
                "State machine for 'lamp': Container state 'self test' of the state machine attached to 'Lamp' uses " +
                "'bulb test', but no fragment is named 'bulb test'\n" +
                "Attachable state machine 'paged': Container state 'page' of state machine 'paged' uses 'other test', " +
                "but no fragment is named 'other test'",
            refusal.message
        )
        assertFalse(EnvironmentHandler.isActiveEnv(envKey))
        assertNull(EnvironmentHandler.executionThreads[envKey])

        APIService.receiveStateMachine(envKey, null, BULB_TEST)
        val second = assertFailsWith<IllegalStateException> { APIService.runEnvironment(envKey) }
        assertTrue(second.message!!.endsWith("Attachable state machine 'paged': Container state 'page' of state " +
            "machine 'paged' uses 'other test', but no fragment is named 'other test'"), second.message)

        APIService.receiveStateMachine(envKey, null, BULB_TEST.replace("\"bulb test\"", "\"other test\""))
        APIService.runEnvironment(envKey)
        assertTrue(EnvironmentHandler.isActiveEnv(envKey))
        assertEquals(SELF_TEST_LAMP, APIService.getStateMachineSource(envKey, LAMP, null))
    }

    @Test
    fun `runEnvironment names a macro call whose arguments do not fit the macro of another file`() {
        val envKey = configured()
        APIService.receiveStateMachine(envKey, null, BULB_TEST.removeSuffix("}") + HALF_MACRO + "\n}")
        APIService.receiveStateMachine(
            envKey, LAMP,
            SELF_TEST_LAMP.replace("INITIAL STATE \"off\" { }", "INITIAL STATE \"off\" { ENTRY { SET(\"watts\", MACRO(\"half\", \"watts\", \"watts\")) } }")
        )

        val refusal = assertFailsWith<IllegalStateException> { APIService.runEnvironment(envKey) }

        assertEquals(
            "Environment with key $envKey has state machines that cannot be flattened:\n" +
                "State machine for 'lamp': In the state machine attached to 'Lamp': Macro 'half' takes 1 argument, " +
                "but the call passes 2; pass one argument per TAKES",
            refusal.message
        )
        assertFalse(EnvironmentHandler.isActiveEnv(envKey))
    }

    @Test
    fun `clients see qualified names in stateChange, in the snapshot and in setActiveState`() {
        val envKey = configured()
        APIService.receiveStateMachine(envKey, null, BULB_TEST)
        APIService.runEnvironment(envKey)
        val accessKey = APIService.registerClientForWebSocket(CLIENT, envKey)
        assertTrue(
            EnvironmentHandler.clientSessionManager.verifyAndAttachSession(CLIENT, envKey, accessKey, MockWebSocketSession())
        )
        subscriptions.subscribe(envKey, CLIENT)

        APIService.startEnvironment(envKey)
        awaitSnapshotStack(envKey, listOf("self test", "self test/lit"))
        EnvironmentHandler.executionThreads.getValue(envKey).engine.receiveExternalEvent(Event(LAMP, "next"))
        awaitSnapshotStack(envKey, listOf("self test", "self test/passed"))
        assertEquals(
            "Unknown state 'lit' for model element 'lamp'",
            assertFailsWith<IllegalArgumentException> { APIService.forceActiveState(envKey, LAMP, "lit") }.message
        )
        APIService.forceActiveState(envKey, LAMP, "self test")
        awaitSnapshotStack(envKey, listOf("self test", "self test/lit"))

        assertEquals(
            listOf(
                listOf("self test", "self test/lit"),
                listOf("self test", "self test/passed"),
                listOf("self test", "self test/lit")
            ),
            stackPushes(envKey)
        )
    }

    /** Waits until the snapshot of [envKey] shows [stack] for the lamp. */
    private fun awaitSnapshotStack(envKey: String, stack: List<String>) =
        Eventually.awaitUntil("the lamp in $stack") { snapshots.snapshot(envKey).activeStates[LAMP] == stack }

    @Test
    fun `the HTTP routes take a fragment and answer a failed activation with its problems`() = testApplication {
        application { HTTPRoutes.configure(this) }
        val envKey = configured()

        val fragment = client.put("/setStateMachine?envKey=$envKey") { setBody(BULB_TEST.replace("\"bulb test\"", "\"other\"")) }
        val attached = client.put("/setStateMachine?envKey=$envKey&attachedTo=lamp") { setBody(BULB_TEST) }
        val refused = client.post("/runEnvironment?envKey=$envKey")
        val upload = client.put("/setStateMachine?envKey=$envKey") { setBody(BULB_TEST) }
        val run = client.post("/runEnvironment?envKey=$envKey")

        assertEquals(HttpStatusCode.OK, fragment.status)
        assertEquals(HttpStatusCode.BadRequest, attached.status)
        assertTrue(attached.bodyAsText().endsWith("upload a fragment without attachedTo"))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(
            "Environment with key $envKey has state machines that cannot be flattened:\n" +
                "State machine for 'lamp': Container state 'self test' of the state machine attached to 'Lamp' uses " +
                "'bulb test', but no fragment is named 'bulb test'",
            refused.bodyAsText()
        )
        assertEquals(HttpStatusCode.OK, upload.status)
        assertEquals(HttpStatusCode.OK, run.status)
    }

    /** Takes every queued message and keeps the stacks of the `stateChange` pushes for [CLIENT] in [envKey]. */
    private fun stackPushes(envKey: String): List<List<String>> =
        generateSequence { EnvironmentHandler.clientSessionManager.nextOutgoingMessage(0) }
            .filter { it.environmentKey == envKey && it.clientId == CLIENT && it.messageType == "stateChange" }
            .map { JSONObject(it.payload).getJSONArray("stack").map { name -> name.toString() } }
            .toList()

    /**
     * Creates an environment with the lamp metamodel and model, [SELF_TEST_LAMP] attached to `lamp` and the attachable
     * [definitions], with a tick delay of 5 ms. No fragment is uploaded.
     */
    private fun configured(definitions: List<String> = emptyList()): String {
        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, METAMODEL)
        APIService.receiveModel(envKey, MODEL)
        APIService.receiveStateMachine(envKey, LAMP, SELF_TEST_LAMP)
        definitions.forEach { APIService.receiveStateMachine(envKey, null, it) }
        APIService.updateTickDelay(envKey, 5)
        return envKey
    }

    private companion object {
        const val CLIENT = "karpfen-ide-pages"
        const val LAMP = "lamp"

        val METAMODEL = """
            type "Lamp" "A lamp" {
                prop("log", list("string"))
                prop("watts", "number")
            }
        """.trimIndent()

        val MODEL = """make object "lamp":"Lamp" { }"""

        /** Lights the bulb and passes on the event `next`. */
        val BULB_TEST = """
            STATEMACHINE "bulb test" FRAGMENT {
                STATES {
                    INITIAL STATE "lit" { ENTRY { APPEND("log", "lit") } }
                    FINAL STATE "passed" { }
                }
                TRANSITIONS {
                    TRANSITION "lit" -> "passed" { CONDITION { EVENT("lamp", "next") } }
                }
            }
        """.trimIndent()

        /** Goes into its self test in the first tick and rests there once the bulb passed. */
        val SELF_TEST_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    CONTAINER STATE "self test" USE "bulb test" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "self test" { }
                }
            }
        """.trimIndent()

        /** A macro block for a fragment: `half` takes one number. */
        val HALF_MACRO = """
            MACROS {
                MACRO "half" {
                    TAKES("watts", "number")
                    RETURNS("number")
                    DEFINITION {
                        EVAL { return $(watts) / 2 }
                    }
                }
            }
        """.trimIndent()

        val PAGED = """
            STATEMACHINE "paged" ATTACHABLE TO "Lamp" {
                STATES {
                    INITIAL CONTAINER STATE "page" USE "other test" { }
                }
                TRANSITIONS { }
            }
        """.trimIndent()
    }
}
