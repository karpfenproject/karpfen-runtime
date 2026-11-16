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

import io.karpfen.env.RegistryEnvironmentLookup
import io.karpfen.fixtures.Eventually
import io.karpfen.fixtures.RuntimeRegistry
import io.karpfen.observation.RunMetricsRoutes
import io.karpfen.observation.RunMetricsService
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
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `GET /observatory/metrics` on a real environment in Ktor's test application, which opens no port: the route refuses
 * an environment before `runEnvironment`, counts the ticks and the conditions the runtime computes itself while the
 * environment runs, and answers the same numbers while it is stopped. The state machine runs no Python.
 */
class RunMetricsServerTest {

    @BeforeEach
    fun resetBefore() {
        RuntimeRegistry.reset()
    }

    @AfterEach
    fun resetAfter() {
        RuntimeRegistry.reset()
    }

    @Test
    fun `the numbers of an environment grow while it runs and stay while it is stopped`() = testApplication {
        application {
            HTTPRoutes.configure(this)
            RunMetricsRoutes(RunMetricsService(RegistryEnvironmentLookup), HTTPRoutes::respondWithError).install(this)
        }
        val envKey = client.post("/createEnvironment").bodyAsText()
        upload(client, "/setMetamodel?envKey=$envKey", METAMODEL)
        upload(client, "/setModel?envKey=$envKey", MODEL)
        upload(client, "/setStateMachine?envKey=$envKey&attachedTo=lamp", LAMP)
        assertEquals(HttpStatusCode.OK, client.post("/setTickDelay?envKey=$envKey&milliseconds=1").status)

        val early = client.get("/observatory/metrics?envKey=$envKey")
        assertEquals(HttpStatusCode.Conflict, early.status)
        assertEquals("Environment with key $envKey is not active", early.bodyAsText())

        assertEquals(HttpStatusCode.OK, client.post("/runEnvironment?envKey=$envKey").status)
        assertEquals(0, metrics(client, envKey).getInt("ticks"))
        assertEquals(HttpStatusCode.OK, client.post("/startEnvironment?envKey=$envKey").status)
        val running = awaitMetrics(client, envKey) { it.getInt("ticks") >= 20 }
        assertEquals("running", running.getString("phase"))

        assertEquals(HttpStatusCode.OK, client.post("/stopEnvironment?envKey=$envKey").status)
        val stopped = metrics(client, envKey)
        assertEquals("stopped", stopped.getString("phase"))
        assertEquals(stopped.toString(), metrics(client, envKey).toString())

        val ticks = stopped.getInt("ticks")
        val calls = stopped.getJSONObject("calls")
        assertEquals(ticks, calls.getJSONObject("computed").getInt("count"), "one computed condition per tick")
        for (way in listOf("newProcess", "openProcess", "cache")) {
            assertEquals(0, calls.getJSONObject(way).getInt("count"), way)
        }
        val tickMs = stopped.getJSONObject("tickMs")
        assertEquals(minOf(ticks, 100), tickMs.getJSONArray("recent").length())
        assertTrue(tickMs.getDouble("max") >= tickMs.getDouble("mean"))
        assertEquals(0.0, stopped.getDouble("pythonMs"))
        assertEquals(tickMs.getDouble("total"), stopped.getDouble("outsidePythonMs"), 1e-6)
    }

    private suspend fun upload(client: HttpClient, route: String, text: String) {
        val response = client.put(route) { setBody(text) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun metrics(client: HttpClient, envKey: String): JSONObject {
        val response = client.get("/observatory/metrics?envKey=$envKey")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return JSONObject(response.bodyAsText())
    }

    /** Reads the numbers until [reached] holds for them, for at most the deadline of [Eventually]. */
    private suspend fun awaitMetrics(client: HttpClient, envKey: String, reached: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + Eventually.DEADLINE_MS
        while (true) {
            val numbers = metrics(client, envKey)
            if (reached(numbers)) return numbers
            if (System.currentTimeMillis() > deadline) fail("The numbers did not get there in time: $numbers")
            delay(10)
        }
    }

    private companion object {
        val METAMODEL = """
            type "Lamp" "A lamp" {
                prop("watts", "number")
            }
        """.trimIndent()

        val MODEL = """make object "lamp":"Lamp" { prop("watts") -> "40" }"""

        /** Looks at one condition in every tick that the runtime computes itself and that never holds. */
        val LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "on" { }
                    STATE "off" { }
                }
                TRANSITIONS {
                    TRANSITION "on" -> "off" {
                        CONDITION { EVAL { return $(watts) < 0 } }
                    }
                }
            }
        """.trimIndent()
    }
}
