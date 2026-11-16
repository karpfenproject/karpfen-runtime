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

import io.karpfen.EngineTraceLogger.TraceEventType
import io.karpfen.fixtures.CleaningRobotExample
import io.karpfen.fixtures.Eventually
import io.karpfen.fixtures.RuntimeRegistry
import io.karpfen.io.karpfen.exec.ModelChangePublisher
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.observation.ModelObservationRoutes
import io.karpfen.observation.ModelSnapshotService
import io.karpfen.observation.ModelSubscriptionService
import io.karpfen.server.APIService
import io.karpfen.server.HTTPRoutes
import io.karpfen.server.MockWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers dropping an environment: in every phase, in the middle of a tick, while TickByTick pauses, while a
 * breakpoint halts a branch under TrueParallel, while a stop or a start is under way, while clients send events, and
 * twice at once. After every drop the key is gone from the registry, no thread of the environment is alive, the
 * sockets of its clients are closed with `Environment dropped`, and every route answers for the key as for an
 * environment that never existed. The state machines use literal actions only, and every wait has a generous deadline.
 */
class EnvironmentDropTest {

    @BeforeEach
    fun resetBefore() {
        RuntimeRegistry.reset()
    }

    @AfterEach
    fun resetAfter() {
        RuntimeRegistry.reset()
    }

    @Test
    fun `a configuring environment is dropped and its key answers like one that never existed`() {
        val envKey = CleaningRobotExample.createEnvironment()
        val socket = connect(envKey)

        APIService.dropEnvironment(envKey)

        assertGone(envKey, null)
        assertClosedAsDropped(socket)
    }

    @Test
    fun `an activated environment that never started is dropped`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)
        val engineId = engineIdOf(envKey)

        APIService.dropEnvironment(envKey)

        assertGone(envKey, engineId)
    }

    @Test
    fun `a running environment is dropped with its engine, its sockets and its trace file`(@TempDir traces: File) {
        val previous = EnvironmentHandler.traceLogDirectory
        EnvironmentHandler.traceLogDirectory = traces.path
        try {
            val envKey = CleaningRobotExample.createEnvironment()
            val socket = connect(envKey)
            APIService.runEnvironment(envKey)
            APIService.startEnvironment(envKey)
            val engineId = engineIdOf(envKey)
            awaitTicks(envKey)

            APIService.dropEnvironment(envKey)

            assertGone(envKey, engineId)
            assertClosedAsDropped(socket)
            val file = File(traces, "engine-trace-$envKey.log")
            assertTrue(file.exists(), "the trace file stays on disk")
            assertTrue(file.readText().contains(TraceEventType.ENGINE_STOP.name), "the trace ends with the stop")
        } finally {
            EnvironmentHandler.traceLogDirectory = previous
        }
    }

    @Test
    fun `a stopped environment is dropped`() {
        val envKey = CleaningRobotExample.createEnvironment()
        val socket = connect(envKey)
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        val engineId = engineIdOf(envKey)
        awaitTicks(envKey)
        APIService.stopEnvironment(envKey)

        APIService.dropEnvironment(envKey)

        assertGone(envKey, engineId)
        assertClosedAsDropped(socket)
    }

    @Test
    fun `a drop in the middle of a tick lets the tick end and ends the engine`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)
        val environment = executionThread(envKey)
        val engineId = environment.engine.engineId
        val inTick = CountDownLatch(1)
        val release = CountDownLatch(1)
        environment.engine.modelQueryProcessor.addChangePublisher(ModelChangePublisher { _, _ ->
            inTick.countDown()
            release.await(Eventually.DEADLINE_MS, TimeUnit.MILLISECONDS)
        })
        APIService.startEnvironment(envKey)
        assertTrue(inTick.await(Eventually.DEADLINE_MS, TimeUnit.MILLISECONDS), "the tick began")

        val dropper = thread(name = "dropper") { APIService.dropEnvironment(envKey) }
        Eventually.awaitUntil("the key left the registry") { EnvironmentHandler.getEnv(envKey) == null }
        release.countDown()
        awaitEnd(dropper)

        assertGone(envKey, engineId)
    }

    @Test
    fun `a drop while TickByTick pauses releases the pause and ends the engine`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.activateFeature(envKey, "TickByTick")
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        val engineId = engineIdOf(envKey)
        awaitTicks(envKey)
        APIService.sendMessage(envKey, "TickByTick", "pause")
        Eventually.awaitUntil("the engine waits") { JSONObject(status(envKey)).getBoolean("waiting") }

        dropWithin(envKey)

        assertGone(envKey, engineId)
    }

    @Test
    fun `a drop while a breakpoint halts a branch under TrueParallel ends both branches`() {
        val envKey = CleaningRobotExample.createEnvironment(HALTING_BRANCHES)
        APIService.activateFeature(envKey, "Breakpoint")
        APIService.activateFeature(envKey, "TrueParallel")
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        val engineId = engineIdOf(envKey)
        Eventually.awaitUntil("a branch halts") { JSONObject(status(envKey)).has("halt") }

        dropWithin(envKey)

        assertGone(envKey, engineId)
    }

    @Test
    fun `a drop of a stopped environment held at a breakpoint ends it`() {
        val envKey = CleaningRobotExample.createEnvironment(HALTING_BRANCHES)
        APIService.activateFeature(envKey, "Breakpoint")
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        val engineId = engineIdOf(envKey)
        Eventually.awaitUntil("a branch halts") { JSONObject(status(envKey)).has("halt") }
        APIService.stopEnvironment(envKey)

        dropWithin(envKey)

        assertGone(envKey, engineId)
    }

    @Test
    fun `drops during stops and starts leave nothing behind`() {
        repeat(ROUNDS) {
            val envKey = CleaningRobotExample.createEnvironment()
            APIService.runEnvironment(envKey)
            APIService.startEnvironment(envKey)
            val engineId = engineIdOf(envKey)
            val barrier = CyclicBarrier(3)
            val cycler = thread(name = "cycler") {
                barrier.await()
                runCatching {
                    APIService.stopEnvironment(envKey)
                    APIService.startEnvironment(envKey)
                    APIService.stopEnvironment(envKey)
                }
            }
            val dropper = thread(name = "dropper") {
                barrier.await()
                APIService.dropEnvironment(envKey)
            }
            barrier.await()
            awaitEnd(cycler)
            awaitEnd(dropper)

            assertGone(envKey, engineId)
        }
    }

    @Test
    fun `a drop while clients send events leaves nothing behind`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        val engineId = engineIdOf(envKey)
        val sending = AtomicBoolean(true)
        val sender = thread(name = "sender") {
            while (sending.get()) {
                EnvironmentHandler.executionThreads[envKey]?.acceptExternalEvent(Event("public", "go"))
                EnvironmentHandler.executionThreads[envKey]?.acceptExternalEvent(Event("public", "back"))
            }
        }
        try {
            awaitTicks(envKey)
            APIService.dropEnvironment(envKey)
        } finally {
            sending.set(false)
            awaitEnd(sender)
        }

        assertGone(envKey, engineId)
    }

    @Test
    fun `of two drops at once one drops the environment and the other finds none`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        val engineId = engineIdOf(envKey)
        val barrier = CyclicBarrier(2)
        val failures = CopyOnWriteArrayList<String>()
        val droppers = (1..2).map {
            thread(name = "dropper-$it") {
                barrier.await()
                try {
                    APIService.dropEnvironment(envKey)
                } catch (e: IllegalArgumentException) {
                    failures += e.message ?: ""
                }
            }
        }
        droppers.forEach(::awaitEnd)

        assertEquals(listOf("Environment with key $envKey does not exist"), failures)
        assertGone(envKey, engineId)
    }

    @Test
    fun `a later environment never gets the key of a dropped one`() {
        val dropped = (1..ROUNDS).map {
            APIService.createEnvironment().also(APIService::dropEnvironment)
        }.toSet()
        val later = (1..ROUNDS).map { APIService.createEnvironment() }

        assertEquals(ROUNDS, dropped.size)
        assertTrue(later.none { it in dropped })
    }

    @Test
    fun `every route answers for a dropped key as for a key that never existed`() = testApplication {
        application {
            HTTPRoutes.configure(this)
            ModelObservationRoutes(
                ModelSnapshotService(RegistryEnvironmentLookup, ModelSnapshotService.DEFAULT_TICK_WAIT_MS),
                ModelSubscriptionService(RegistryEnvironmentLookup, EnvironmentHandler.clientSessionManager),
                HTTPRoutes::respondWithError
            ).install(this)
        }
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        awaitTicks(envKey)

        val drop = client.post("/dropEnvironment?envKey=$envKey")
        assertEquals(HttpStatusCode.OK, drop.status)
        assertEquals("", drop.bodyAsText())

        val never = "env-never"
        for ((method, path) in ROUTES) {
            suspend fun answer(key: String): Pair<HttpStatusCode, String> {
                val url = "$path?envKey=$key&$PARAMETERS"
                val response = when (method) {
                    "GET" -> client.get(url)
                    "PUT" -> client.put(url) { setBody("x") }
                    else -> client.post(url) { setBody("x") }
                }
                return response.status to response.bodyAsText().replace(key, "<envKey>")
            }
            assertEquals(answer(never), answer(envKey), "$method $path")
        }
        val missing = client.post("/dropEnvironment")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("Missing required parameter: envKey", missing.bodyAsText())
        val listing = client.get("/observatory/environments").bodyAsText()
        assertFalse(listing.contains(envKey))
    }

    /** Registers the client `ide` for [envKey] and attaches a mock socket for it. */
    private fun connect(envKey: String): MockWebSocketSession {
        val accessKey = APIService.registerClientForWebSocket("ide", envKey)
        val socket = MockWebSocketSession()
        assertTrue(EnvironmentHandler.clientSessionManager.verifyAndAttachSession("ide", envKey, accessKey, socket))
        return socket
    }

    /** Checks that [socket] was closed with the reason of a dropped environment, and only with it. */
    private fun assertClosedAsDropped(socket: MockWebSocketSession) {
        Eventually.awaitUntil("the socket was closed") { socket.getCloseReasons().isNotEmpty() }
        assertEquals(listOf(CloseReason(CloseReason.Codes.NORMAL, "Environment dropped")), socket.getCloseReasons())
    }

    /**
     * Checks that nothing of [envKey] stays: no entry in the registry and the listings, no session or access key, no
     * message waiting for it, and no live thread of its runner, its engine with id [engineId] or its branches.
     */
    private fun assertGone(envKey: String, engineId: String?) {
        assertNull(EnvironmentHandler.getEnv(envKey))
        assertFalse(EnvironmentHandler.activeEnvs.containsKey(envKey))
        assertFalse(EnvironmentHandler.executionThreads.containsKey(envKey))
        assertTrue(APIService.listActiveEnvironments().none { it["envKey"] == envKey })
        assertNull(EnvironmentHandler.clientSessionManager.getSessionForClient(envKey, "ide"))
        assertFalse(EnvironmentHandler.clientSessionManager.verifyAndAttachSession("ide", envKey, "any", MockWebSocketSession()))
        val queued = generateSequence { EnvironmentHandler.clientSessionManager.nextOutgoingMessage(0) }.toList()
        assertTrue(queued.none { it.environmentKey == envKey }, "messages for the dropped environment: $queued")
        val alive = Thread.getAllStackTraces().keys.filter { thread ->
            thread.isAlive && (thread.name == "EnvironmentThread-$envKey" || (engineId != null && thread.name.contains(engineId)))
        }
        assertEquals(emptyList(), alive.map { it.name }, "threads of the dropped environment")
        assertFailsWith<IllegalArgumentException> { APIService.startEnvironment(envKey) }
    }

    /** Drops [envKey] on a thread of its own and fails when the drop does not return within the generous deadline. */
    private fun dropWithin(envKey: String) {
        val dropper = thread(name = "dropper") { APIService.dropEnvironment(envKey) }
        awaitEnd(dropper)
    }

    /** Waits for [worker] to end and fails when it does not within the generous deadline. */
    private fun awaitEnd(worker: Thread) {
        worker.join(Eventually.DEADLINE_MS)
        if (worker.isAlive) fail("${worker.name} did not end")
    }

    /** Waits until the engine of [envKey] ran a few ticks. */
    private fun awaitTicks(envKey: String) {
        val engine = executionThread(envKey).engine
        Eventually.awaitUntil("a few ticks") { engine.tickCount >= 3 }
    }

    /** The status of the engine of [envKey] as TickByTick reports it. */
    private fun status(envKey: String): String =
        assertNotNull(EnvironmentHandler.envs[envKey]).featureManager
            .getActiveFeatureOrNull<io.karpfen.io.karpfen.features.runtime.TickByTickFeature>()!!.engineStatus()

    private fun engineIdOf(envKey: String): String = executionThread(envKey).engine.engineId

    private fun executionThread(envKey: String): EnvironmentThread =
        assertNotNull(EnvironmentHandler.executionThreads[envKey])

    private companion object {
        /** How often the tests that race repeat. */
        const val ROUNDS = 5

        /** Every route that takes an environment key, with its method. */
        val ROUTES = listOf(
            "PUT" to "/setMetamodel", "PUT" to "/setModel", "PUT" to "/setEventDefinitions", "PUT" to "/setStateMachine",
            "POST" to "/setTickDelay", "POST" to "/setEventTtl", "POST" to "/setActiveState",
            "POST" to "/registerObjectObserver", "POST" to "/registerDomainListener",
            "POST" to "/registerClientForWebSocket", "POST" to "/runEnvironment", "POST" to "/startEnvironment",
            "POST" to "/stopEnvironment", "POST" to "/dropEnvironment", "GET" to "/observatory/statemachine",
            "GET" to "/observatory/stateMachines", "POST" to "/observatory/registerClient", "GET" to "/observatory/model",
            "POST" to "/observatory/subscribeModel", "POST" to "/observatory/unsubscribeModel",
            "GET" to "/getActiveFeatures", "POST" to "/activateFeature", "POST" to "/deactivateFeature",
            "POST" to "/sendMessageToFeature"
        )

        /** The other parameters of those routes, all given to each. */
        const val PARAMETERS = "modelElement=turtle&state=idle&milliseconds=5&ttlMs=5&clientId=ide&objectId=turtle" +
            "&domain=public&featureName=TickByTick&name=x&attachedTo=turtle"

        /** Splits into two branches that both halt at the entry of their state. */
        val HALTING_BRANCHES = """
            STATEMACHINE ATTACHED TO "Robot" {
                STATES {
                    INITIAL STATE "init" { }
                    STATE "left" {
                        BREAKPOINT { ENTRY }
                        ENTRY { APPEND("log", "left") }
                    }
                    STATE "right" {
                        BREAKPOINT { ENTRY }
                        ENTRY { APPEND("log", "right") }
                    }
                }
                TRANSITIONS {
                    SPLIT "init" -> "left", "right" { }
                }
            }
        """.trimIndent()
    }
}
