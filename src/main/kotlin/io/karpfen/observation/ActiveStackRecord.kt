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

import java.util.Collections

/**
 * The last reported active stack of every state machine of one environment, kept for model snapshots.
 *
 * The engine reports a machine's stack after its first tick following a start and then whenever its end-of-tick stack
 * differs from the last reported one. The record keeps the machines in the order they first reported. It is written
 * on the engine thread and read by snapshot requests, so every member is synchronized and [current] hands out an
 * immutable copy.
 */
class ActiveStackRecord {

    /** Stack per machine (`attachedTo` key), in the order machines first reported. */
    private val stacks = LinkedHashMap<String, List<String>>()

    /** Records [stack] as the active stack of the machine attached to [modelElementId]. */
    @Synchronized
    fun record(modelElementId: String, stack: List<String>) {
        stacks[modelElementId] = Collections.unmodifiableList(ArrayList(stack))
    }

    /** An immutable copy of the recorded stacks, in the order machines first reported. */
    @Synchronized
    fun current(): Map<String, List<String>> = Collections.unmodifiableMap(LinkedHashMap(stacks))

    /** Forgets every recorded stack, as a start does before the machines report again. */
    @Synchronized
    fun clear() {
        stacks.clear()
    }
}
