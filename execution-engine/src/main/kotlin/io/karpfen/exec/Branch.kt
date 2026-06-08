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
package io.karpfen.io.karpfen.exec

import io.karpfen.io.karpfen.messages.Event
import states.TransitionLike

/**
 * One independently advancing region of a state machine: a single current state stack with its own
 * tick bookkeeping. A simple (non-parallel) context has exactly one branch; a parallel context has
 * several, ticked in order.
 *
 * A branch holds only *data* — all behaviour lives in the shared per-context services, which are
 * handed this branch's data each tick. Each branch carries its own [eventProcessor] identity so that
 * parallel branches can react to the same event independently.
 *
 * @property eventProcessor    This branch's view of the shared event bus (its consumption lineage).
 * @property stateStack        Active states, outermost first; the last entry is the current state.
 * @property notEnteredSubstack States whose ENTRY block still needs to run, in order.
 * @property lastFiredTransition The transition this branch fired last (for the NOT LOOPING rule).
 * @property scopedEventByState The event that brought the branch into each active state, by state name.
 */
class Branch(
    val eventProcessor: EventProcessor,
    var stateStack: List<String>,
    var notEnteredSubstack: MutableList<String>,
    var lastFiredTransition: TransitionLike? = null,
    val scopedEventByState: MutableMap<String, Event> = mutableMapOf()
) {

    /** The current (innermost) state of this branch. */
    fun currentState(): String = stateStack.last()

    /**
     * The event currently in scope: the scoped event of the innermost active state that has one.
     * Auto-entered substates without their own event transparently inherit the enclosing event.
     */
    fun currentScopedEvent(): Event? {
        for (i in stateStack.indices.reversed()) {
            val event = scopedEventByState[stateStack[i]]
            if (event != null) return event
        }
        return null
    }
}
