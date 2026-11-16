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

import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.language.HistoryFeature

/**
 * Finds the stack a branch rests on when it enters a state, for every way a state is entered during a run: the target
 * of a transition, of a split and of a join, the target of a tick limit timeout and of a history, both of which the
 * engine fires as transitions, and the state that `setActiveState` forces.
 *
 * Entering a container state goes down into the copy of its fragment, as [StateMachineQueryHelper.getEnteredStack]
 * describes, so a branch never rests on a container state alone. The histories come from the [HistoryFeature] while it
 * is switched on; without it every container state is entered along the start path of its fragment. The start of an
 * instance needs no history and takes [StateMachineQueryHelper.getInitialStateStack].
 *
 * @property featureManager the features of the environment, asked for the [HistoryFeature] at each entry.
 */
class StateEntry(private val featureManager: FeatureManager) {

    /**
     * Returns the stack the branch of [ctx] rests on after entering the states of [targetStack], the path from the top
     * to the target, outermost first.
     */
    fun restingStack(ctx: SMContext, targetStack: List<String>): List<String> {
        val history = featureManager.getActiveFeatureOrNull<HistoryFeature>()
        return ctx.smQueryHelper.getEnteredStack(targetStack) { state ->
            history?.remembered(ctx.modelElementId, state)
        }
    }
}
