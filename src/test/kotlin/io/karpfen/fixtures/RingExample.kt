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

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import instance.Model

/**
 * A model whose `knows` relations form a loop: the room-like `ring` holds the nodes `a` and `b`, and each node knows
 * the other one. Such a model has no JSON because `knows` relations are inlined.
 */
object RingExample {

    /** The metamodel of the ring. */
    val metamodel: String = """
        type "Node" "A node that knows the next node" {
            prop("visits", "number")
            knows("next", "Node")
        }
        type "Ring" "Holds the nodes of a reference loop" {
            has("nodes", list("Node"))
        }
    """.trimIndent()

    /** The model of the ring with the loop `a` to `b` to `a`. */
    val model: String = """
        make object "ring":"Ring" {
            has("nodes") -> make object "a":"Node" {
                prop("visits") -> "0"
                knows("next") -> "b"
            }
            has("nodes") -> make object "b":"Node" {
                prop("visits") -> "0"
                knows("next") -> "a"
            }
        }
    """.trimIndent()

    /** Parses the ring into a model. */
    fun parse(): Model = KmodelDSLConverter.parseKmodelString(model, KmetaDSLConverter.parseKmetaString(metamodel))
}
