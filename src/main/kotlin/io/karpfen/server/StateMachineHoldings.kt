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
package io.karpfen.server

import io.karpfen.io.karpfen.exec.ModelJsonWriter

/**
 * The state machines one environment holds, as `GET /observatory/stateMachines` answers them.
 *
 * @property attached the keys of the objects that have a state machine uploaded with `attachedTo`, in upload order.
 * @property definitions the attachable state machines, each its name and the types it lists, in upload order.
 * @property instances the state machine instances, each the key of its object and the name of its definition (null for
 *   an attached state machine), in the order in which they step; empty before `runEnvironment`, the instances the start
 *   will build after it, and the instances of the latest tick once ticks have run.
 */
data class StateMachineHoldings(
    val attached: List<String>,
    val definitions: List<Pair<String, List<String>>>,
    val instances: List<Pair<String, String?>>
) {

    /**
     * Writes the holdings as one JSON object with the members `attached`, `definitions` (objects with `name` and
     * `types`) and `instances` (objects with `modelElement` and `stateMachine`, which is `null` for an attached state
     * machine), members in this order and without whitespace. Strings are quoted with [ModelJsonWriter.quote].
     */
    fun toJson(): String {
        val keys = attached.joinToString(",") { quote(it) }
        val named = definitions.joinToString(",") { (name, types) ->
            "{\"name\":${quote(name)},\"types\":[${types.joinToString(",") { quote(it) }}]}"
        }
        val running = instances.joinToString(",") { (objectId, definition) ->
            "{\"modelElement\":${quote(objectId)},\"stateMachine\":${definition?.let(::quote) ?: "null"}}"
        }
        return "{\"attached\":[$keys],\"definitions\":[$named],\"instances\":[$running]}"
    }

    private fun quote(text: String): String = ModelJsonWriter.quote(text)
}
