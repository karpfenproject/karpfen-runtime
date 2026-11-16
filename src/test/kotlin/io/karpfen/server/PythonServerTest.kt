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
import io.karpfen.io.karpfen.messages.PayloadFormat
import io.karpfen.observation.ModelObservationRoutes
import io.karpfen.observation.ModelSnapshotService
import io.karpfen.observation.ModelSubscriptionService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * A small lamp scenario with Python, uploaded, run, read and dropped through the HTTP API in Ktor's test application,
 * which opens no port: code with quotes, backslashes, line breaks and characters outside ASCII sets a property, an event
 * with such a text reaches code in a new process and in the open process, and dropping the environment ends its
 * Python processes.
 */
@Tag("python")
class PythonServerTest {

    @BeforeEach
    fun resetBefore() {
        RuntimeRegistry.reset()
    }

    @AfterEach
    fun resetAfter() {
        RuntimeRegistry.reset()
    }

    @Test
    fun `a scenario with Python runs through the HTTP API and its values arrive unchanged`() = testApplication {
        application {
            HTTPRoutes.configure(this)
            ModelObservationRoutes(
                ModelSnapshotService(RegistryEnvironmentLookup, ModelSnapshotService.DEFAULT_TICK_WAIT_MS),
                ModelSubscriptionService(RegistryEnvironmentLookup, EnvironmentHandler.clientSessionManager),
                HTTPRoutes::respondWithError
            ).install(this)
        }
        val before = pythonProcesses()
        val envKey = client.post("/createEnvironment").bodyAsText()
        upload(client, "/setMetamodel?envKey=$envKey", METAMODEL)
        upload(client, "/setModel?envKey=$envKey", MODEL)
        upload(client, "/setEventDefinitions?envKey=$envKey", EVENTS)
        upload(client, "/setStateMachine?envKey=$envKey&attachedTo=lamp", LAMP)
        assertEquals(HttpStatusCode.OK, client.post("/setTickDelay?envKey=$envKey&milliseconds=5").status)
        assertEquals(HttpStatusCode.OK, client.post("/runEnvironment?envKey=$envKey").status)
        assertEquals(HttpStatusCode.OK, client.post("/startEnvironment?envKey=$envKey").status)

        val text = "say \"hi\", it's a back\\slash\nand a tab\t: Grüße 日本 💡"
        val payload = JSONObject().put("text", text).toString()
        assertNotNull(EnvironmentHandler.executionThreads[envKey])
            .acceptExternalEvent(Event("t", "say", payload, payloadFormat = PayloadFormat.JSON))
        val lamp = awaitLamp(client, envKey) { it.optJSONArray("log")?.length() == 2 }

        assertEquals("Grüße \"a\" 'b' \\ \n2", lamp.getString("label"))
        assertEquals(listOf(text, text), (0 until 2).map { lamp.getJSONArray("log").getString(it) })
        assertEquals(1, (pythonProcesses() - before).size, "the open process of the lamp")

        assertEquals(HttpStatusCode.OK, client.post("/dropEnvironment?envKey=$envKey").status)
        Eventually.awaitUntil("the open process ends with the environment") { (pythonProcesses() - before).isEmpty() }
    }

    private suspend fun upload(client: HttpClient, route: String, text: String) {
        val response = client.put(route) { setBody(text) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    /** Reads the lamp from `GET /observatory/model` until [reached] holds for it, for at most 10 s. */
    private suspend fun awaitLamp(client: HttpClient, envKey: String, reached: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + Eventually.DEADLINE_MS
        while (true) {
            val response = client.get("/observatory/model?envKey=$envKey")
            if (response.status == HttpStatusCode.OK) {
                val lamp = JSONObject(response.bodyAsText()).getJSONArray("roots").getJSONObject(0)
                if (reached(lamp)) return lamp
            }
            if (System.currentTimeMillis() > deadline) fail("The lamp did not get there in time: ${response.bodyAsText()}")
            delay(10)
        }
    }

    /** The process ids of the Python processes this JVM started and that still run. */
    private fun pythonProcesses(): Set<Long> = ProcessHandle.current().descendants()
        .filter { it.isAlive }
        .filter { handle -> handle.info().command().map { it.contains("python") }.orElse(false) }
        .map { it.pid() }
        .toList()
        .toSet()

    private companion object {
        val METAMODEL = """
            type "Lamp" "A lamp" {
                prop("label", "string")
                prop("log", list("string"))
            }
        """.trimIndent()

        val MODEL = """make object "lamp":"Lamp" { prop("label") -> "plain" }"""

        val EVENTS = """
            type "say" "Something to say" {
                prop("text", "string")
            }
        """.trimIndent()

        val LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" {
                        ENTRY { SET("label", EVAL { return "Grüße \"a\" 'b' \\ \n" + str(len([1, 2])) }) }
                    }
                    STATE "on" {
                        ENTRY {
                            APPEND("log", EVAL { return $(event->text) })
                            APPEND("log", EVAL { return @echo($(event->text)) })
                        }
                    }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "on" {
                        CONDITION {
                            EVENT("t", "say")
                            EVAL { return len($(event->text)) > 0 }
                        }
                    }
                }
                MACROS {
                    MACRO "echo" {
                        TAKES("value", "string")
                        RETURNS("string")
                        DEFINITION { EVAL { return $(value) } }
                    }
                }
            }
        """.trimIndent()
    }
}
