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
 * Conditions in each shape of [LampWorld.condition], looked at in every tick: computed by the runtime itself, plain
 * code in Python and code in Python that calls a macro, with one, five and twenty transitions that leave the active state. None of the
 * conditions holds, so every tick looks at all of them. The score is the time of one tick.
 */
open class ConditionBenchmark : EngineBenchmark() {

    @Param("computed", "plain", "macro")
    var way: String = "computed"

    @Param("1", "5", "20")
    var transitions: Int = 1

    override fun scenario() = Scenario(LampWorld.METAMODEL, LampWorld.MODEL, mapOf("lamp" to LampWorld.conditions(way, transitions)))

    @Benchmark
    @OperationsPerInvocation(TICKS)
    fun tick() = ticks(TICKS)

    private companion object {
        const val TICKS = 10
    }
}
