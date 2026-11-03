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
package io.karpfen.observation

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import io.karpfen.fixtures.CleaningRobotExample
import io.karpfen.io.karpfen.exec.ModelJsonWriter
import io.karpfen.server.MockWebSocketSession
import io.karpfen.websocket.ClientSessionManager
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [ModelObservationRoutes] in Ktor's test application, which opens no port: parameter checks in their order,
 * error statuses, the session check of subscribing, empty success bodies, and the snapshot's status, content type and
 * member order.
 */
class ModelObservationRoutesTest {

    /** The environments the routes find. */
    private val environments = FakeEnvironments()

    /** The sessions and subscriptions of the routes. */
    private val sessions = ClientSessionManager()

    @Test
    fun `a snapshot without envKey is refused with the missing-parameter text`() = observationApplication {
        assertAnswer(HttpStatusCode.BadRequest, "Missing required parameter: envKey", client.get("/observatory/model"))
    }

    @Test
    fun `a snapshot of an unknown environment is refused`() = observationApplication {
        assertAnswer(
            HttpStatusCode.BadRequest,
            "Environment with key env-404 does not exist",
            client.get("/observatory/model?envKey=env-404")
        )
    }

    @Test
    fun `a snapshot of a configuring environment without a model is a conflict`() = observationApplication {
        environments.create(ENV)

        assertAnswer(
            HttpStatusCode.Conflict,
            "Environment with key $ENV has no model",
            client.get("/observatory/model?envKey=$ENV")
        )
    }

    @Test
    fun `the snapshot is answered as UTF-8 JSON with its members in order`() = observationApplication {
        val model = KmodelDSLConverter.parseKmodelString(
            CleaningRobotExample.model,
            KmetaDSLConverter.parseKmetaString(CleaningRobotExample.metamodel)
        )
        val root = ModelJsonWriter.write(model.objects.single())
        val running = ModelSnapshot(
            ENV,
            EnvironmentPhase.RUNNING,
            57L,
            linkedMapOf("turtle" to listOf("observe", "react to obstacle"), "lamp" to listOf("off")),
            listOf(root)
        )
        environments.create(ENV, model)
        environments.activate(ENV, FakeObservedEnvironment(EnvironmentPhase.RUNNING, running))

        val response = client.get("/observatory/model?envKey=$ENV")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/json; charset=UTF-8", response.headers[HttpHeaders.ContentType])
        assertEquals(
            "{\"environmentKey\":\"$ENV\",\"phase\":\"running\",\"revision\":57," +
                "\"activeStates\":{\"turtle\":[\"observe\",\"react to obstacle\"],\"lamp\":[\"off\"]}," +
                "\"roots\":[$root]}",
            response.bodyAsText()
        )
    }

    @Test
    fun `subscribing and unsubscribing refuse missing parameters in the order envKey then clientId`() =
        observationApplication {
            for (route in listOf("/observatory/subscribeModel", "/observatory/unsubscribeModel")) {
                val missingEnvKey = "Missing required parameter: envKey"
                assertAnswer(HttpStatusCode.BadRequest, missingEnvKey, client.post(route))
                assertAnswer(HttpStatusCode.BadRequest, missingEnvKey, client.post("$route?clientId=ide"))
                assertAnswer(
                    HttpStatusCode.BadRequest,
                    "Missing required parameter: clientId",
                    client.post("$route?envKey=env-404")
                )
            }
        }

    @Test
    fun `subscribing and unsubscribing refuse an unknown environment`() = observationApplication {
        for (route in listOf("/observatory/subscribeModel", "/observatory/unsubscribeModel")) {
            assertAnswer(
                HttpStatusCode.BadRequest,
                "Environment with key env-404 does not exist",
                client.post("$route?envKey=env-404&clientId=ide")
            )
        }
    }

    @Test
    fun `subscribing without an open WebSocket session is a conflict`() = observationApplication {
        environments.create(ENV)

        assertAnswer(
            HttpStatusCode.Conflict,
            "Client ide has no open WebSocket session for environment $ENV",
            client.post("/observatory/subscribeModel?envKey=$ENV&clientId=ide")
        )
        assertFalse(sessions.isSubscribedToModel(ENV, "ide"))
    }

    @Test
    fun `subscribe and unsubscribe answer 200 with an empty body`() = observationApplication {
        environments.create(ENV)
        val accessKey = sessions.registerClientAccess("ide", ENV)
        assertTrue(sessions.verifyAndAttachSession("ide", ENV, accessKey, MockWebSocketSession()))

        assertAnswer(HttpStatusCode.OK, "", client.post("/observatory/subscribeModel?envKey=$ENV&clientId=ide"))
        assertTrue(sessions.isSubscribedToModel(ENV, "ide"))
        assertAnswer(HttpStatusCode.OK, "", client.post("/observatory/subscribeModel?envKey=$ENV&clientId=ide"))
        assertTrue(sessions.isSubscribedToModel(ENV, "ide"))

        assertAnswer(HttpStatusCode.OK, "", client.post("/observatory/unsubscribeModel?envKey=$ENV&clientId=ide"))
        assertFalse(sessions.isSubscribedToModel(ENV, "ide"))
        assertAnswer(HttpStatusCode.OK, "", client.post("/observatory/unsubscribeModel?envKey=$ENV&clientId=ide"))
    }

    /** Runs [test] against a test application with the observation routes over [environments]. */
    private fun observationApplication(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            ModelObservationRoutes(
                ModelSnapshotService(environments, TICK_WAIT_MS),
                ModelSubscriptionService(environments, sessions)
            ).install(this)
        }
        test()
    }

    /** Asserts that [response] has [status] and exactly [body]. */
    private suspend fun assertAnswer(status: HttpStatusCode, body: String, response: HttpResponse) {
        assertEquals(status, response.status)
        assertEquals(body, response.bodyAsText())
    }

    private companion object {
        /** The environment of these tests. */
        const val ENV = "env-1761560123456"

        /** The wait for a tick boundary of the routes' snapshot service. */
        const val TICK_WAIT_MS = 1_500L
    }
}
