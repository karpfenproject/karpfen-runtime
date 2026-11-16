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
 * The same condition with the same values in every tick, which a store of results could answer, against values that
 * change in every tick, which it could not. A computed action in the DO block adds 0 or 1 to the value the condition
 * reads, so both variants do the same work besides the condition. The condition runs in Python as plain code or as code
 * that calls a macro. The score is the time of one tick.
 */
open class RepeatedValuesBenchmark : EngineBenchmark() {

    @Param("plain", "macro")
    var way: String = "plain"

    @Param("unchanged", "changing")
    var values: String = "unchanged"

    override fun scenario(): Scenario {
        val step = if (values == "changing") 1 else 0
        val doBlock = "DO { SET(\"watts\", EVAL { return $(watts) + $step }) }"
        return Scenario(LampWorld.METAMODEL, LampWorld.MODEL, mapOf("lamp" to LampWorld.conditions(way, 1, doBlock)))
    }

    @Benchmark
    @OperationsPerInvocation(TICKS)
    fun tick() = ticks(TICKS)

    private companion object {
        const val TICKS = 10
    }
}
