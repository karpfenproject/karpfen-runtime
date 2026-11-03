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
package io.karpfen.env

import io.karpfen.fixtures.CleaningRobotExample
import io.karpfen.fixtures.Eventually
import io.karpfen.fixtures.RuntimeRegistry
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.server.APIService
import io.karpfen.server.MockWebSocketSession
import io.ktor.websocket.CloseReason
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers starting and stopping an activated environment: one runner loop per environment, restarts after a stop, and
 * what a stop does to the environment's WebSocket sessions.
 */
class EnvironmentRunnerTest {

    /** Starts from an empty registry and an empty outgoing queue. */
    @BeforeEach
    fun resetBefore() {
        RuntimeRegistry.reset()
    }

    /** Leaves an empty registry and an empty outgoing queue for the next test. */
    @AfterEach
    fun resetAfter() {
        RuntimeRegistry.reset()
    }

    @Test
    fun `starting a running environment keeps a single runner loop`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)

        APIService.startEnvironment(envKey)
        APIService.startEnvironment(envKey)
        APIService.startEnvironment(envKey)

        assertEquals(1, liveRunners(envKey))
        assertTrue(executionThread(envKey).engine.isTicking)
    }

    @Test
    fun `starting a stopped environment runs it again`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        val environment = executionThread(envKey)
        Eventually.awaitUntil("the engine ticked") { distanceToObstacle(environment) == 1.0 }

        APIService.stopEnvironment(envKey)
        assertFalse(environment.engine.isTicking)
        assertEquals(0, liveRunners(envKey))

        APIService.startEnvironment(envKey)
        environment.acceptExternalEvent(Event("public", "go"))

        assertEquals(1, liveRunners(envKey))
        Eventually.awaitUntil("the restarted environment handled the event") { distanceToObstacle(environment) == 2.0 }
        assertTrue(environment.engine.isTicking)
    }

    @Test
    fun `stopping closes the environment's WebSocket sessions with a normal close`() {
        val envKey = CleaningRobotExample.createEnvironment()
        val sessions = EnvironmentHandler.clientSessionManager
        val accessKey = APIService.registerClientForWebSocket("ide", envKey)
        val socket = MockWebSocketSession()
        assertTrue(sessions.verifyAndAttachSession("ide", envKey, accessKey, socket))
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)

        APIService.stopEnvironment(envKey)

        assertNull(sessions.getSessionForClient(envKey, "ide"))
        Eventually.awaitUntil("the socket was closed") { socket.getCloseReasons().isNotEmpty() }
        assertEquals(listOf(CloseReason(CloseReason.Codes.NORMAL, "Environment stopped")), socket.getCloseReasons())
    }

    /** The execution thread of the activated environment [envKey]. */
    private fun executionThread(envKey: String): EnvironmentThread =
        assertNotNull(EnvironmentHandler.executionThreads[envKey])

    /** The number of live runner threads of [envKey], recognized by their name. */
    private fun liveRunners(envKey: String): Int =
        Thread.getAllStackTraces().keys.count { it.name == "EnvironmentThread-$envKey" && it.isAlive }

    /** The turtle's `d_closest_obstacle`, read between two ticks. */
    private fun distanceToObstacle(environment: EnvironmentThread): Double? =
        environment.engine.readBetweenTicks(Eventually.DEADLINE_MS) {
            environment.engine.modelQueryProcessor.getDataObjectById("turtle").getProp("d_closest_obstacle").first()
                as Double
        }
}
