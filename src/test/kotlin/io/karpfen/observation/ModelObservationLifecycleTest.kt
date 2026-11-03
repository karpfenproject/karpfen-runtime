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

import io.karpfen.env.EnvironmentHandler
import io.karpfen.env.EnvironmentThread
import io.karpfen.env.RegistryEnvironmentLookup
import io.karpfen.fixtures.CleaningRobotExample
import io.karpfen.fixtures.Eventually
import io.karpfen.fixtures.RuntimeRegistry
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.server.APIService
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Covers model observation over real environments built through [APIService] from the cleaning-robot example, with
 * the literal machine that moves `turtle` between `idle` and `step` on the events `go` and `back` of the domain
 * `public`. Snapshots come from a [ModelSnapshotService] over the runtime's registry; events are injected with
 * [EnvironmentThread.acceptExternalEvent].
 */
class ModelObservationLifecycleTest {

    /** Snapshots over the runtime's registry with the default wait for a tick boundary. */
    private val snapshots = ModelSnapshotService(RegistryEnvironmentLookup, ModelSnapshotService.DEFAULT_TICK_WAIT_MS)

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

    /** The `turtle` object inside the room of [snapshot], parsed from its root. */
    private fun turtle(snapshot: ModelSnapshot): JSONObject =
        JSONObject(snapshot.roots.single()).getJSONObject("robot")
}
