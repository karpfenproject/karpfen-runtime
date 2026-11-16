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
 * Values of different size handed to code in Python, one condition per tick that never holds:
 *
 * - `number`: a number;
 * - `string`: a string of 1000 characters;
 * - `smallObject`: a lamp with three properties and a bulb with two;
 * - `longList`: a meter object with a list of 10000 numbers;
 * - `manyObjects`: a wing whose `$(wing)` reaches 200 lamps with a bulb each.
 *
 * The code measures the length of the text of the value, so Python reads all of it. With `plain` the code calls no
 * macro, with `macro` it also calls one. The score is the time of one tick.
 */
open class ValueSizeBenchmark : EngineBenchmark() {

    @Param("number", "string", "smallObject", "longList", "manyObjects")
    var value: String = "number"

    @Param("plain", "macro")
    var way: String = "plain"

    override fun scenario(): Scenario {
        val path = when (value) {
            "number" -> "$(count)"
            "string" -> "$(name)"
            "smallObject" -> "$(main)"
            "longList" -> "$(meter)"
            "manyObjects" -> "$(wing)"
            else -> throw IllegalArgumentException("Unknown value: $value")
        }
        val code = if (way == "macro") "return len(str($path)) < @zero()" else "return len(str($path)) < 0"
        return Scenario(METAMODEL, MODEL, mapOf("house" to house(code)))
    }

    @Benchmark
    @OperationsPerInvocation(TICKS)
    fun tick() = ticks(TICKS)

    private companion object {
        const val TICKS = 10

        val METAMODEL = """
            type "House" "A house" {
                prop("count", "number")
                prop("name", "string")
                has("main", "Lamp")
                has("meter", "Meter")
                has("wing", "Wing")
            }
            type "Wing" "A wing of the house" {
                has("lamps", list("Lamp"))
            }
            type "Meter" "A power meter" {
                prop("unit", "string")
                prop("readings", list("number"))
            }
            type "Lamp" "A lamp" {
                prop("label", "string")
                prop("watts", "number")
                prop("on", "boolean")
                has("bulb", "Bulb")
            }
            type "Bulb" "A light bulb" {
                prop("label", "string")
                prop("watts", "number")
            }
        """

        val MODEL = """
            make object "house":"House" {
                prop("count") -> "42"
                prop("name") -> "${"house ".repeat(167).take(1000)}"
                has("main") -> ${lamp("main")}
                has("meter") -> make object "meter":"Meter" {
                    prop("unit") -> "W"
                    ${(1..10_000).joinToString("\n") { "prop(\"readings\") -> \"${it % 997}.5\"" }}
                }
                has("wing") -> make object "wing":"Wing" {
                    ${(1..200).joinToString("\n") { "has(\"lamps\") -> ${lamp("lamp$it")}" }}
                }
            }
        """

        fun lamp(key: String) = """
            make object "$key":"Lamp" {
                prop("label") -> "lamp $key"
                prop("watts") -> "40"
                prop("on") -> "false"
                has("bulb") -> make object "bulb of $key":"Bulb" {
                    prop("label") -> "warm white"
                    prop("watts") -> "40"
                }
            }
        """

        fun house(code: String) = """
            STATEMACHINE ATTACHED TO "House" {
                STATES {
                    INITIAL STATE "watching" { }
                    STATE "alarm" { }
                }
                TRANSITIONS {
                    TRANSITION "watching" -> "alarm" { CONDITION { EVAL { $code } } }
                }
                MACROS {
                    MACRO "zero" {
                        RETURNS("number")
                        DEFINITION { EVAL { return 0 } }
                    }
                }
            }
        """
    }
}
