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

import io.karpfen.fixtures.Eventually
import io.karpfen.fixtures.RuntimeRegistry
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.server.APIService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A state forced through the route `setActiveState` of an environment that is stopped: the events that clients sent
 * during the stop reach the bus at the start, before the forced state is applied, so they count as handled by the forced
 * state machine, and every other state machine still gets them. The state machines run no Python, and every wait polls
 * with a generous deadline.
 */
class ForcedStateEnvironmentTest {

    @BeforeEach
    fun resetBefore() {
        RuntimeRegistry.reset()
    }

    @AfterEach
    fun resetAfter() {
        RuntimeRegistry.reset()
    }

    @Test
    fun `the events sent while stopped stay for other state machines and count as handled by the forced one`() {
        val envKey = configured()
        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)
        val environment = assertNotNull(EnvironmentHandler.executionThreads[envKey])
        awaitStack(environment, "robot", "asleep")
        awaitStack(environment, "watcher", "waiting")

        APIService.stopEnvironment(envKey)
        environment.acceptExternalEvent(Event("orders", "go"))
        APIService.forceActiveState(envKey, "robot", "ready")
        APIService.startEnvironment(envKey)

        awaitStack(environment, "watcher", "seen")
        awaitStack(environment, "robot", "ready")
        val reached = environment.engine.tickCount
        Eventually.awaitUntil("three more ticks") { environment.engine.tickCount >= reached + 3 }
        assertEquals(listOf("ready"), environment.activeStacks.current()["robot"])

        environment.acceptExternalEvent(Event("orders", "go"))
        awaitStack(environment, "robot", "moving")
    }

    /** Waits until the last reported stack of [objectId] is [state]. */
    private fun awaitStack(environment: EnvironmentThread, objectId: String, state: String) =
        Eventually.awaitUntil("the stack [$state] of $objectId") {
            environment.activeStacks.current()[objectId] == listOf(state)
        }

    /** Creates an environment with the site, its robot and its watcher, with a tick delay of 5 ms. */
    private fun configured(): String {
        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, METAMODEL)
        APIService.receiveModel(envKey, MODEL)
        APIService.receiveStateMachine(envKey, "robot", ROBOT)
        APIService.receiveStateMachine(envKey, "watcher", WATCHER)
        APIService.updateTickDelay(envKey, 5)
        return envKey
    }

    private companion object {
        val METAMODEL = """
            type "Site" "Where the robot and the watcher are" {
                has("robot", "Robot")
                has("watcher", "Watcher")
            }
            type "Robot" "A robot" {
            }
            type "Watcher" "Watches the orders of the robot" {
            }
        """.trimIndent()

        val MODEL = """
            make object "site":"Site" {
                has("robot") -> make object "robot":"Robot" { }
                has("watcher") -> make object "watcher":"Watcher" { }
            }
        """.trimIndent()

        /** Rests in `asleep`, which no event leaves; `ready` is reached only by force and left on `go`. */
        val ROBOT = """
            STATEMACHINE ATTACHED TO "Robot" {
                STATES {
                    INITIAL STATE "asleep" { }
                    STATE "ready" { }
                    STATE "moving" { }
                }
                TRANSITIONS {
                    TRANSITION "ready" -> "moving" { CONDITION { EVENT("orders", "go") } }
                }
            }
        """.trimIndent()

        /** Goes to `seen` on `go`. */
        val WATCHER = """
            STATEMACHINE ATTACHED TO "Watcher" {
                STATES {
                    INITIAL STATE "waiting" { }
                    STATE "seen" { }
                }
                TRANSITIONS {
                    TRANSITION "waiting" -> "seen" { CONDITION { EVENT("orders", "go") } }
                }
            }
        """.trimIndent()
    }
}
