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
package io.karpfen.fixtures

import io.karpfen.server.APIService
import java.io.File

/**
 * The cleaning-robot example of the runtime's `examples` directory with literal state machines that never run
 * Python, for tests that build real environments through [APIService].
 */
object CleaningRobotExample {

    /** The example's domain metamodel. */
    val metamodel: String = exampleFile("cleaning_robot.kmeta")

    /** The example's model; its robot is the object `turtle` inside the room `APB 2101`. */
    val model: String = exampleFile("cleaning_robot.kmodel")

    /**
     * A machine for `turtle` that rests in `idle` and moves to `step` on the event `go` of the domain `public` and
     * back on `back`. Each state's `ENTRY` block writes literals only.
     */
    val idleStepMachine: String = """
        STATEMACHINE ATTACHED TO "Robot" {
            STATES {
                INITIAL STATE "idle" {
                    ENTRY {
                        SET("d_closest_obstacle", "1.0")
                    }
                }
                STATE "step" {
                    ENTRY {
                        SET("d_closest_obstacle", "2.0")
                        APPEND("log", "step")
                    }
                }
            }
            TRANSITIONS {
                TRANSITION "idle" -> "step" {
                    CONDITION {
                        EVENT("public", "go")
                    }
                }
                TRANSITION "step" -> "idle" {
                    CONDITION {
                        EVENT("public", "back")
                    }
                }
            }
        }
    """.trimIndent()

    /**
     * Creates a configured environment through [APIService]: the example's metamodel and model, [machine] attached to
     * [attachedTo], and a short tick delay. The environment is not activated.
     *
     * @return the new environment's key.
     */
    fun createEnvironment(machine: String = idleStepMachine, attachedTo: String = "turtle"): String {
        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, metamodel)
        APIService.receiveModel(envKey, model)
        APIService.receiveStateMachine(envKey, attachedTo, machine)
        APIService.updateTickDelay(envKey, TICK_DELAY_MS)
        return envKey
    }

    /** Reads a file of the single-robot example, from the runtime directory or from a subproject directory. */
    private fun exampleFile(name: String): String {
        val candidates = listOf("examples/single_robot_example/$name", "../examples/single_robot_example/$name")
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Example file $name not found in $candidates")
        return file.readText()
    }

    /** The tick delay of the environments these fixtures create. */
    private const val TICK_DELAY_MS = 20
}
