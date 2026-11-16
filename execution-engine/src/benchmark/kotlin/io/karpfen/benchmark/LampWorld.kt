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

/**
 * The lamp world of the benchmarks: a lamp with a bulb, and conditions that never hold, written so that their code runs
 * in each of the ways of the runtime. A condition that never holds keeps the lamp in its state, so the same conditions
 * are looked at in every tick.
 */
object LampWorld {

    val METAMODEL = """
        type "Lamp" "A lamp" {
            prop("label", "string")
            prop("watts", "number")
            prop("log", list("string"))
            has("bulb", "Bulb")
        }
        type "Bulb" "A light bulb" {
            prop("label", "string")
            prop("watts", "number")
        }
    """

    val MODEL = """
        make object "lamp":"Lamp" {
            prop("label") -> "desk lamp"
            prop("watts") -> "40"
            has("bulb") -> make object "bulb":"Bulb" {
                prop("label") -> "warm white"
                prop("watts") -> "40"
            }
        }
    """

    /** The macro that the conditions of the way `macro` call. */
    val MACROS = """
        MACROS {
            MACRO "negative" {
                TAKES("value", "number")
                RETURNS("boolean")
                DEFINITION { EVAL { return $(value) < 0 } }
            }
        }
    """

    /**
     * The code of a condition on [value] that never holds, in the shape [way]: `computed`, which the runtime computes
     * itself, `plain`, which runs in Python and calls no macro, or `macro`, which runs in Python and calls a macro. The
     * values of the properties are not negative.
     */
    fun condition(way: String, value: String = "$(watts)"): String = when (way) {
        "computed" -> "EVAL { return $value < 0 }"
        "plain" -> "EVAL { return abs($value) < 0 }"
        "macro" -> "EVAL { return @negative($value) }"
        else -> throw IllegalArgumentException("Unknown way: $way")
    }

    /** A lamp in the state `on` with [transitions] transitions that each look at a condition of [way] in every tick. */
    fun conditions(way: String, transitions: Int, doBlock: String = ""): String = """
        STATEMACHINE ATTACHED TO "Lamp" {
            STATES {
                INITIAL STATE "on" { $doBlock }
                STATE "off" { }
            }
            TRANSITIONS {
                ${(1..transitions).joinToString("\n") { "TRANSITION \"on\" -> \"off\" { CONDITION { ${condition(way)} } }" }}
            }
            $MACROS
        }
    """
}
