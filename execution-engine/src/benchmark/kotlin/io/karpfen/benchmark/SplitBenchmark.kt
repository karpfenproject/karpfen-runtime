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

import io.karpfen.io.karpfen.features.runtime.TrueParallelFeature
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import org.openjdk.jmh.annotations.OperationsPerInvocation

/**
 * A lamp that splits into four branches, each of which runs code in a new Python process in its DO block in every
 * tick, with the feature TrueParallel off, where the branches step one after the other, and on, where they step at
 * the same time on threads of their own. The score is the time of one tick.
 */
open class SplitBenchmark : EngineBenchmark() {

    @Param("false", "true")
    var trueParallel: Boolean = false

    override fun scenario() = Scenario(
        LampWorld.METAMODEL,
        LampWorld.MODEL,
        mapOf("lamp" to SPLITTING_LAMP),
        features = if (trueParallel) listOf(TrueParallelFeature::class) else emptyList()
    )

    @Benchmark
    @OperationsPerInvocation(TICKS)
    fun tick() = ticks(TICKS)

    private companion object {
        const val TICKS = 10

        val SPLITTING_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "init" { }
                    ${(1..4).joinToString("\n") { "STATE \"s$it\" { DO { SET(\"label\", EVAL { return \"branch \" + str(abs($it)) }) } }" }}
                }
                TRANSITIONS {
                    SPLIT "init" -> "s1", "s2", "s3", "s4" { }
                }
            }
        """
    }
}
