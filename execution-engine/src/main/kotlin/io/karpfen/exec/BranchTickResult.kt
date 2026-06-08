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
import states.SplitTransition

/**
 * What ticking a single branch produced.
 *
 * A branch handles its own local changes (running actions, taking a normal transition in place,
 * burning unreacted events). The one thing it cannot do alone is change the *shape* of the context:
 * a split turns one branch into many. So a branch reports [SplitRequested] and lets the context-level
 * orchestration carry it out, which keeps the branch logic free of any parallel-structure concerns.
 */
sealed interface BranchTickResult {
    /** Nothing structural happened (idle, or a normal transition was applied in place). */
    object Stable : BranchTickResult

    /** This branch's current state wants to split into several parallel branches. */
    data class SplitRequested(val split: SplitTransition, val matchedEvent: Event?) : BranchTickResult
}
