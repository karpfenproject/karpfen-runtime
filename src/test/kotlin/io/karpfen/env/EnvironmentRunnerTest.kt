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
import io.karpfen.io.karpfen.exec.ModelChangePublisher
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventPublicationListener
import io.karpfen.io.karpfen.messages.PayloadFormat
import io.karpfen.observation.EnvironmentPhase
import io.karpfen.server.APIService
import io.karpfen.server.MockWebSocketSession
import io.ktor.websocket.CloseReason
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers starting and stopping an activated environment: one runner loop per environment, restarts after a stop, the
 * phase reported while a start or a stop is under way, a runner loop that ends on its own or survives a payload it
 * cannot parse, and what a stop does to the environment's WebSocket sessions.
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
    fun `a start or restart under way is never reported as halted`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)
        val environment = executionThread(envKey)
        val polling = AtomicBoolean(true)
        val phases = ConcurrentHashMap.newKeySet<EnvironmentPhase>()
        val poller = thread(name = "phase-poller") {
            while (polling.get()) {
                phases.add(environment.phase)
            }
        }

        try {
            repeat(RESTARTS) {
                APIService.startEnvironment(envKey)
                APIService.stopEnvironment(envKey)
            }
        } finally {
            polling.set(false)
            poller.join()
        }

        assertTrue(EnvironmentPhase.STOPPED in phases, "Phases seen: $phases")
        assertFalse(EnvironmentPhase.HALTED in phases, "Phases seen: $phases")
    }

    @Test
    fun `a stop is reported as stopped only once the engine's last tick has ended`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        val environment = executionThread(envKey)
        Eventually.awaitUntil("the engine ticked") { distanceToObstacle(environment) == 1.0 }
        val tickEntered = CountDownLatch(1)
        val tickReleased = CountDownLatch(1)
        environment.engine.modelQueryProcessor.addChangePublisher(ModelChangePublisher { _, _ ->
            tickEntered.countDown()
            tickReleased.await(Eventually.DEADLINE_MS, TimeUnit.MILLISECONDS)
        })
        environment.acceptExternalEvent(Event("public", "go"))
        assertTrue(tickEntered.await(Eventually.DEADLINE_MS, TimeUnit.MILLISECONDS), "The step's tick never began")

        val stopper = thread(name = "stopper") { environment.stop() }
        try {
            Eventually.awaitUntil("the stop waits for the engine's last tick") {
                stopper.state == Thread.State.TIMED_WAITING
            }
            assertEquals(EnvironmentPhase.RUNNING, environment.phase)
        } finally {
            tickReleased.countDown()
            stopper.join()
        }

        assertEquals(EnvironmentPhase.STOPPED, environment.phase)
    }

    @Test
    fun `an environment whose runner loop ended through an error is reported as halted and starts again`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        val environment = executionThread(envKey)
        Eventually.awaitUntil("the engine ticked") { distanceToObstacle(environment) == 1.0 }
        val fatal = EventPublicationListener { throw Error("No handler of the runner loop catches this") }
        environment.sharedEventBus.addPublicationListener(fatal)

        environment.acceptExternalEvent(Event("public", "go"))

        Eventually.awaitUntil("the runner loop ended") { liveRunners(envKey) == 0 }
        assertFalse(environment.engine.isTicking)
        assertEquals(EnvironmentPhase.HALTED, environment.phase)

        environment.sharedEventBus.removePublicationListener(fatal)
        APIService.startEnvironment(envKey)

        assertEquals(EnvironmentPhase.RUNNING, environment.phase)
        assertEquals(1, liveRunners(envKey))
        assertTrue(environment.engine.isTicking)
    }

    @Test
    fun `a client event nested too deeply to parse still fires and the environment keeps running`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.receiveEventDefinitions(envKey, NESTING_EVENTS)
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        val environment = executionThread(envKey)
        Eventually.awaitUntil("the engine ticked") { distanceToObstacle(environment) == 1.0 }

        environment.acceptExternalEvent(Event("public", "go", deeplyNestedGo(), payloadFormat = PayloadFormat.KMODEL))

        Eventually.awaitUntil("the event fired by name") { distanceToObstacle(environment) == 2.0 }
        assertEquals(EnvironmentPhase.RUNNING, environment.phase)
        assertEquals(1, liveRunners(envKey))
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

    /** A kmodel payload of the event `go` that nests [TOO_DEEP] objects, each inside the one before. */
    private fun deeplyNestedGo(): String {
        val payload = StringBuilder()
        repeat(TOO_DEEP) { level ->
            if (level > 0) payload.append("has(\"inner\") -> ")
            payload.append("make object \"go-").append(level).append("\":\"go\" { ")
        }
        repeat(TOO_DEEP) { payload.append("} ") }
        return payload.toString()
    }

    private companion object {
        /** How many start and stop cycles the phase poller watches. */
        const val RESTARTS = 30

        /** Event definitions whose one event type `go` may hold another `go`. */
        val NESTING_EVENTS = """
            type "go" "a step whose payload nests itself" {
                has("inner", "go")
            }
        """.trimIndent()

        /** A nesting far deeper than the payload parser's recursion reaches on a thread's default stack. */
        const val TOO_DEEP = 20_000
    }
}
