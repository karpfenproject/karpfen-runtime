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

import io.karpfen.io.karpfen.metrics.CodeCalls
import io.karpfen.io.karpfen.metrics.CodeWay
import io.karpfen.io.karpfen.metrics.RunMetricsSnapshot
import io.karpfen.server.HTTPRoutes
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [RunMetricsRoutes] in Ktor's test application, which opens no port: the errors for a missing parameter, an
 * unknown environment and an environment that is not active, and the exact answer with its members in order.
 */
class RunMetricsRoutesTest {

    /** The environments the route finds. */
    private val environments = FakeEnvironments()

    @Test
    fun `the numbers without envKey are refused with the missing-parameter text`() = metricsApplication {
        assertAnswer(HttpStatusCode.BadRequest, "Missing required parameter: envKey", client.get("/observatory/metrics"))
    }

    @Test
    fun `the numbers of an unknown environment are refused`() = metricsApplication {
        assertAnswer(
            HttpStatusCode.BadRequest,
            "Environment with key env-404 does not exist",
            client.get("/observatory/metrics?envKey=env-404")
        )
    }

    @Test
    fun `the numbers of an environment that is not active are a conflict`() = metricsApplication {
        environments.create(ENV)

        assertAnswer(
            HttpStatusCode.Conflict,
            "Environment with key $ENV is not active",
            client.get("/observatory/metrics?envKey=$ENV")
        )
    }

    @Test
    fun `the numbers are answered as JSON with milliseconds and the members in order`() = metricsApplication {
        environments.create(ENV)
        environments.activate(ENV, FakeObservedEnvironment(EnvironmentPhase.STOPPED, null, METRICS))

        val response = client.get("/observatory/metrics?envKey=$ENV")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith(ContentType.Application.Json.toString()))
        assertEquals(
            "{\"environmentKey\":\"$ENV\",\"phase\":\"stopped\",\"ticks\":3," +
                "\"tickMs\":{\"total\":31.5,\"mean\":10.5,\"max\":20,\"recent\":[1.5,10,20]}," +
                "\"pythonMs\":2.000001,\"outsidePythonMs\":29.499999," +
                "\"calls\":{\"computed\":{\"count\":4,\"ms\":0.012}," +
                "\"openProcess\":{\"count\":2,\"ms\":2.000001},\"cache\":{\"count\":0,\"ms\":0}}}",
            response.bodyAsText()
        )
    }

    @Test
    fun `an environment before its first tick answers zeros`() = metricsApplication {
        environments.create(ENV)
        environments.activate(ENV, FakeObservedEnvironment(EnvironmentPhase.ACTIVATED, null))

        assertAnswer(
            HttpStatusCode.OK,
            "{\"environmentKey\":\"$ENV\",\"phase\":\"activated\",\"ticks\":0," +
                "\"tickMs\":{\"total\":0,\"mean\":0,\"max\":0,\"recent\":[]},\"pythonMs\":0,\"outsidePythonMs\":0," +
                "\"calls\":{\"computed\":{\"count\":0,\"ms\":0}," +
                "\"openProcess\":{\"count\":0,\"ms\":0},\"cache\":{\"count\":0,\"ms\":0}}}",
            client.get("/observatory/metrics?envKey=$ENV")
        )
    }

    /** Runs [test] against a test application with the metrics route over [environments]. */
    private fun metricsApplication(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            RunMetricsRoutes(RunMetricsService(environments), HTTPRoutes::respondWithError).install(this)
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

        /** Three ticks of 1.5, 10 and 20 ms, with calls of every way but the cache. */
        val METRICS = RunMetricsSnapshot(
            ticks = 3,
            tickNanos = 31_500_000,
            maxTickNanos = 20_000_000,
            recentTickNanos = listOf(1_500_000, 10_000_000, 20_000_000),
            outsidePythonNanos = 29_499_999,
            calls = mapOf(
                CodeWay.COMPUTED to CodeCalls(4, 12_000),
                CodeWay.OPEN_PROCESS to CodeCalls(2, 2_000_001),
                CodeWay.CACHE to CodeCalls(0, 0)
            )
        )
    }
}
