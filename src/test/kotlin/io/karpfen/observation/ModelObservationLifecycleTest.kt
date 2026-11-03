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

import instance.DataObject
import instance.SimpleAtomicPropertyObject
import io.karpfen.env.EnvironmentHandler
import io.karpfen.env.EnvironmentThread
import io.karpfen.env.RegistryEnvironmentLookup
import io.karpfen.fixtures.CleaningRobotExample
import io.karpfen.fixtures.Eventually
import io.karpfen.fixtures.RuntimeRegistry
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.server.APIService
import io.karpfen.server.MockWebSocketSession
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers model observation over real environments built through [APIService] from the cleaning-robot example, with
 * the literal machine that moves `turtle` between `idle` and `step` on the events `go` and `back` of the domain
 * `public`. Snapshots come from a [ModelSnapshotService] over the runtime's registry; events are injected with
 * [EnvironmentThread.acceptExternalEvent].
 */
class ModelObservationLifecycleTest {

    /** Snapshots over the runtime's registry with the default wait for a tick boundary. */
    private val snapshots = ModelSnapshotService(RegistryEnvironmentLookup, ModelSnapshotService.DEFAULT_TICK_WAIT_MS)

    /** Model subscriptions over the runtime's registry and its session manager. */
    private val subscriptions =
        ModelSubscriptionService(RegistryEnvironmentLookup, EnvironmentHandler.clientSessionManager)

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
    fun `the snapshot reports each lifecycle phase`() {
        val envKey = CleaningRobotExample.createEnvironment()
        val configuring = snapshots.snapshot(envKey)
        assertEquals(EnvironmentPhase.CONFIGURING to 0L, configuring.phase to configuring.revision)

        APIService.runEnvironment(envKey)
        val activated = snapshots.snapshot(envKey)
        assertEquals(EnvironmentPhase.ACTIVATED to 0L, activated.phase to activated.revision)
        assertEquals(emptyMap(), activated.activeStates)
        assertEquals(configuring.roots, activated.roots)

        APIService.startEnvironment(envKey)
        assertEquals(EnvironmentPhase.RUNNING, snapshots.snapshot(envKey).phase)
        awaitStack(envKey, "idle")

        APIService.stopEnvironment(envKey)
        assertEquals(EnvironmentPhase.STOPPED, snapshots.snapshot(envKey).phase)
    }

    @Test
    fun `the snapshot reports halted when the engine ended on its own`() {
        val envKey = CleaningRobotExample.createEnvironment(attachedTo = "ghost")
        val environment = EnvironmentThread(assertNotNull(EnvironmentHandler.getEnv(envKey)))
        environment.setup()
        EnvironmentHandler.executionThreads[envKey] = environment

        environment.launch()

        Eventually.awaitUntil("the engine thread ended") { snapshots.snapshot(envKey).phase == EnvironmentPhase.HALTED }
        val halted = snapshots.snapshot(envKey)
        assertEquals(0L, halted.revision)
        assertEquals(emptyMap(), halted.activeStates)
        assertEquals(100.0, turtle(halted).getDouble("d_closest_obstacle"))
    }

    @Test
    fun `switching observation on loses and repeats nothing`() {
        val envKey = startedEnvironment()
        awaitStack(envKey, "idle")
        attachSession(envKey)
        subscriptions.subscribe(envKey, CLIENT)
        val environment = executionThread(envKey)

        environment.acceptExternalEvent(Event("public", "go"))
        val first = snapshots.snapshot(envKey)
        awaitTurtle(envKey, "step", stepEntries = 1)
        environment.acceptExternalEvent(Event("public", "back"))
        awaitTurtle(envKey, "idle", stepEntries = 1)
        environment.acceptExternalEvent(Event("public", "go"))
        awaitTurtle(envKey, "step", stepEntries = 2)
        val next = snapshots.snapshot(envKey)

        val pushes = drainPushes(envKey)
        val mirror = SnapshotMirror(first)
        pushes.filter { it.second.getLong("revision") > first.revision }
            .forEach { (messageType, payload) -> mirror.apply(messageType, payload) }
        assertTrue(mirror.matches(next), "Mirror at ${mirror.revision} differs from the snapshot at ${next.revision}")
        assertEquals(
            ((first.revision + 1)..next.revision).toList(),
            pushes.map { it.second.getLong("revision") }.filter { it > first.revision }
        )
    }

    @Test
    fun `switching observation off stops new object and stack pushes`() {
        val envKey = startedEnvironment()
        awaitStack(envKey, "idle")
        attachSession(envKey)
        subscriptions.subscribe(envKey, CLIENT)
        val environment = executionThread(envKey)
        environment.acceptExternalEvent(Event("public", "go"))
        awaitTurtle(envKey, "step", stepEntries = 1)
        assertEquals(listOf("stateChange", "objectChanged"), drainPushes(envKey).map { it.first })

        subscriptions.unsubscribe(envKey, CLIENT)
        val revisionWhenOff = snapshots.snapshot(envKey).revision
        environment.acceptExternalEvent(Event("public", "back"))
        awaitTurtle(envKey, "idle", stepEntries = 1)

        assertEquals(revisionWhenOff + 2, snapshots.snapshot(envKey).revision)
        assertEquals(emptyList(), drainPushes(envKey))
    }

    @Test
    fun `run-time and deleted objects reach a model subscriber`() {
        val envKey = startedEnvironment()
        awaitStack(envKey, "idle")
        attachSession(envKey)
        subscriptions.subscribe(envKey, CLIENT)
        val engine = executionThread(envKey).engine
        val modelQueryProcessor = engine.modelQueryProcessor

        val created = assertNotNull(engine.readBetweenTicks(Eventually.DEADLINE_MS) {
            val vectorType = assertNotNull(engine.metamodel.getTypeByName("Vector"))
            val vector = DataObject(
                vectorType,
                modelQueryProcessor.generateUniqueId("Vector"),
                mutableListOf(
                    SimpleAtomicPropertyObject(vectorType.getSimpleProperty("x"), "x", 1.0),
                    SimpleAtomicPropertyObject(vectorType.getSimpleProperty("y"), "y", 0.0)
                ),
                mutableListOf()
            )
            modelQueryProcessor.setObjectRelation("turtle", "direction", vector)
            vector.id
        })
        engine.readBetweenTicks(Eventually.DEADLINE_MS) { modelQueryProcessor.updateProperty(created, "x", 3.0) }
        engine.readBetweenTicks(Eventually.DEADLINE_MS) { modelQueryProcessor.dropObject(created) }

        val pushes = drainPushes(envKey)
        assertEquals(
            listOf(
                "objectChanged" to "turtle",
                "objectDeleted" to "turtleDirection",
                "objectChanged" to "turtle",
                "objectChanged" to created,
                "objectChanged" to "turtle",
                "objectDeleted" to created
            ),
            pushes.map { (messageType, payload) -> messageType to payload.getString("objectId") }
        )
        val revisions = pushes.map { it.second.getLong("revision") }
        assertEquals((revisions.first() until revisions.first() + pushes.size).toList(), revisions)
        assertEquals(created, pushes[2].second.getJSONObject("value").getJSONObject("direction").getString("__id__"))
        assertEquals(3, pushes[3].second.getJSONObject("value").getInt("x"))
        assertTrue(pushes[4].second.getJSONObject("value").isNull("direction"))
    }

    @Test
    fun `the final snapshot after a stop holds the last changes`() {
        val envKey = startedEnvironment()
        awaitStack(envKey, "idle")
        executionThread(envKey).acceptExternalEvent(Event("public", "go"))
        awaitStack(envKey, "step")
        Eventually.awaitUntil("the ENTRY block of step ran") {
            turtle(snapshots.snapshot(envKey)).optJSONArray("log")?.length() == 1
        }
        val beforeStop = snapshots.snapshot(envKey)

        APIService.stopEnvironment(envKey)

        val final = snapshots.snapshot(envKey)
        assertEquals(EnvironmentPhase.STOPPED, final.phase)
        assertEquals(beforeStop.copy(phase = EnvironmentPhase.STOPPED), final)
        assertEquals(mapOf("turtle" to listOf("step")), final.activeStates)
        assertEquals(2.0, turtle(final).getDouble("d_closest_obstacle"))
        assertEquals(listOf<Any>("step"), turtle(final).getJSONArray("log").toList())
    }

    @Test
    fun `revisions keep counting across a restart`() {
        val envKey = startedEnvironment()
        awaitStack(envKey, "idle")
        assertEquals(2L, snapshots.snapshot(envKey).revision)

        APIService.stopEnvironment(envKey)
        APIService.startEnvironment(envKey)

        awaitStack(envKey, "idle")
        assertEquals(4L, snapshots.snapshot(envKey).revision)
    }

    /** Creates, activates and starts an environment with the idle and step machine on `turtle`. */
    private fun startedEnvironment(): String {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        return envKey
    }

    /** The execution thread of the activated environment [envKey]. */
    private fun executionThread(envKey: String): EnvironmentThread =
        assertNotNull(EnvironmentHandler.executionThreads[envKey])

    /** Waits until the snapshot of [envKey] reports `turtle` in exactly [state]. */
    private fun awaitStack(envKey: String, state: String) {
        Eventually.awaitUntil("turtle reported the stack [$state]") {
            snapshots.snapshot(envKey).activeStates == mapOf("turtle" to listOf(state))
        }
    }

    /** Waits until `turtle` rests in [state] after `step` was entered [stepEntries] times, judged by snapshots. */
    private fun awaitTurtle(envKey: String, state: String, stepEntries: Int) {
        val distance = if (state == "step") 2.0 else 1.0
        Eventually.awaitUntil("turtle rests in $state after $stepEntries entries of step") {
            val snapshot = snapshots.snapshot(envKey)
            val turtle = turtle(snapshot)
            snapshot.activeStates == mapOf("turtle" to listOf(state)) &&
                turtle.getDouble("d_closest_obstacle") == distance &&
                (turtle.optJSONArray("log")?.length() ?: 0) == stepEntries
        }
    }

    /** Registers the IDE's client for [envKey] and attaches a mock WebSocket session with its access key. */
    private fun attachSession(envKey: String) {
        val accessKey = APIService.registerClientForWebSocket(CLIENT, envKey)
        val attached = EnvironmentHandler.clientSessionManager
            .verifyAndAttachSession(CLIENT, envKey, accessKey, MockWebSocketSession())
        assertTrue(attached)
    }

    /** Takes every queued message and keeps the pushes for [CLIENT] in [envKey] as message type and payload. */
    private fun drainPushes(envKey: String): List<Pair<String, JSONObject>> =
        generateSequence { EnvironmentHandler.clientSessionManager.nextOutgoingMessage(0) }
            .filter { it.environmentKey == envKey && it.clientId == CLIENT }
            .map { it.messageType to JSONObject(it.payload) }
            .toList()

    /** The `turtle` object inside the room of [snapshot], parsed from its root. */
    private fun turtle(snapshot: ModelSnapshot): JSONObject =
        JSONObject(snapshot.roots.single()).getJSONObject("robot")

    private companion object {
        /** The client id the tests subscribe with, like the IDE's. */
        const val CLIENT = "karpfen-ide-4f2c9a1b7e03"
    }
}
