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
import io.karpfen.fixtures.RuntimeRegistry
import io.karpfen.server.APIService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Covers activating environments through [APIService] and [EnvironmentHandler]: activation happens once and
 * atomically.
 */
class EnvironmentActivationTest {

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
    fun `a second runEnvironment is refused and the first execution thread stays in charge`() {
        val envKey = CleaningRobotExample.createEnvironment()
        APIService.runEnvironment(envKey)
        val first = assertNotNull(EnvironmentHandler.executionThreads[envKey])

        val refusal = assertFailsWith<IllegalStateException> { APIService.runEnvironment(envKey) }

        assertEquals(
            "Environment with key $envKey is already active and cannot be modified in the intended way.",
            refusal.message
        )
        assertSame(first, EnvironmentHandler.executionThreads[envKey])
        APIService.startEnvironment(envKey)
        assertSame(first, EnvironmentHandler.executionThreads[envKey])
    }

    @Test
    fun `a refused activation leaves the environment configurable`() {
        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, CleaningRobotExample.metamodel)

        assertFailsWith<NullPointerException> { EnvironmentHandler.activeEnv(envKey) }

        assertFalse(EnvironmentHandler.isActiveEnv(envKey))
        assertNull(EnvironmentHandler.executionThreads[envKey])
        APIService.receiveModel(envKey, CleaningRobotExample.model)
        APIService.receiveStateMachine(envKey, "turtle", CleaningRobotExample.idleStepMachine)
        APIService.runEnvironment(envKey)
        assertNotNull(EnvironmentHandler.executionThreads[envKey])
    }
}
