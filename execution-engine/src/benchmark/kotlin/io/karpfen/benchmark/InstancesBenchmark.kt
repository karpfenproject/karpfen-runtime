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
package io.karpfen.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import org.openjdk.jmh.annotations.OperationsPerInvocation

/**
 * Many state machine instances: a room with ten, fifty or two hundred lamps whose type `behaves after` the attachable
 * state machine `glowing`, so every lamp runs an instance of its own. Each instance looks at one condition in Python
 * in every tick, with the result cache of the engine off or on. Since all lamps have the same watts, the instances
 * share the results of the cache. The score is the time of one tick of all instances.
 */
open class InstancesBenchmark : EngineBenchmark() {

    @Param("10", "50", "200")
    var lamps: Int = 10

    @Param("off", "on")
    var cache: String = "off"

    override fun scenario() = Scenario(
        METAMODEL,
        """
            make object "room":"Room" {
                ${(1..lamps).joinToString("\n") { "has(\"lamps\") -> make object \"lamp$it\":\"Lamp\" { prop(\"watts\") -> \"40\" }" }}
            }
        """,
        attached = emptyMap(),
        definitions = listOf(GLOWING),
        resultCaching = cache == "on"
    )

    @Benchmark
    @OperationsPerInvocation(TICKS)
    fun tick() = ticks(TICKS)

    private companion object {
        const val TICKS = 1

        val METAMODEL = """
            type "Room" "A room" {
                has("lamps", list("Lamp"))
            }
            type "Lamp" "A lamp" behaves after "glowing" {
                prop("watts", "number")
            }
        """

        val GLOWING = """
            STATEMACHINE "glowing" ATTACHABLE TO "Lamp" {
                STATES {
                    INITIAL STATE "on" { }
                    STATE "off" { }
                }
                TRANSITIONS {
                    TRANSITION "on" -> "off" { CONDITION { ${LampWorld.condition("plain")} } }
                }
            }
        """
    }
}
