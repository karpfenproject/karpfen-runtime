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
package io.karpfen.observation

import io.karpfen.io.karpfen.exec.ModelJsonWriter

/**
 * The whole model of one environment at one revision, as `GET /observatory/model` answers it.
 *
 * A snapshot of a running environment is read between two ticks, so the snapshot at [revision] contains exactly the
 * effects of all observations up to that revision and nothing after it. The value is immutable: its creators hand in
 * fresh copies that nobody changes afterwards, so it may cross threads.
 *
 * @property environmentKey the environment the snapshot describes.
 * @property phase the environment's lifecycle phase when the snapshot was read.
 * @property revision the revision of the last observation the snapshot contains; 0 before anything happened.
 * @property activeStates per state machine (`attachedTo` key) the last reported active stack, in the order machines
 *   first reported; empty before the first tick after a start.
 * @property roots every root object of the model in model order, each written by [ModelJsonWriter.write] exactly as
 *   the `value` of an `objectChanged` push.
 */
data class ModelSnapshot(
    val environmentKey: String,
    val phase: EnvironmentPhase,
    val revision: Long,
    val activeStates: Map<String, List<String>>,
    val roots: List<String>
) {

    /**
     * Writes the snapshot as one JSON object with the members `environmentKey`, `phase`, `revision`, `activeStates`
     * and `roots` in this order and without whitespace between them. Strings are quoted with [ModelJsonWriter.quote];
     * the roots are spliced in as written.
     */
    fun toJson(): String {
        val states = activeStates.entries.joinToString(",") { (modelElementId, stack) ->
            "${ModelJsonWriter.quote(modelElementId)}:[${stack.joinToString(",") { ModelJsonWriter.quote(it) }}]"
        }
        return "{\"environmentKey\":${ModelJsonWriter.quote(environmentKey)}," +
            "\"phase\":${ModelJsonWriter.quote(phase.wireName)}," +
            "\"revision\":$revision," +
            "\"activeStates\":{$states}," +
            "\"roots\":[${roots.joinToString(",")}]}"
    }
}
