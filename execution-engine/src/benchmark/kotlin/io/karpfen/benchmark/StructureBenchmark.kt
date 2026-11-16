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
import org.openjdk.jmh.annotations.OperationsPerInvocation

/**
 * The cost of finding transitions and states without Python: fifty states in five container states of ten, and two
 * hundred transitions, four leaving each state. Three of them have a condition that never holds and that needs no code,
 * the fourth leads on to the next state, so the lamp walks through all states in a circle, one state per tick, and
 * every tick looks at four transitions and enters a state. The score is the time of one tick.
 */
open class StructureBenchmark : EngineBenchmark() {

    override fun scenario() = Scenario(LampWorld.METAMODEL, LampWorld.MODEL, mapOf("lamp" to WALKING_LAMP))

    @Benchmark
    @OperationsPerInvocation(TICKS)
    fun tick() = ticks(TICKS)

    private companion object {
        const val TICKS = 100
        const val STATES = 50

        private fun state(i: Int) = "s$i"

        val WALKING_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    ${(0 until STATES / 10).joinToString("\n") { group ->
                        "STATE \"g$group\" {\n" + (0 until 10).joinToString("\n") { i ->
                            val n = group * 10 + i
                            "${if (n == 0) "INITIAL " else ""}STATE \"${state(n)}\" { }"
                        } + "\n}"
                    }}
                }
                TRANSITIONS {
                    ${(0 until STATES).joinToString("\n") { n ->
                        val from = state(n)
                        val next = state((n + 1) % STATES)
                        val skip = (1..3).joinToString("\n") { k ->
                            "TRANSITION \"$from\" -> \"${state((n + 7 * k) % STATES)}\" { CONDITION { VALUE(\"false\") } }"
                        }
                        "$skip\nTRANSITION \"$from\" -> \"$next\" { }"
                    }}
                }
            }
        """
    }
}
