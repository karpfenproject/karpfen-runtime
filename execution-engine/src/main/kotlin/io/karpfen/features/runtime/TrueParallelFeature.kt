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
package io.karpfen.io.karpfen.features.runtime

import com.google.auto.service.AutoService
import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import kotlin.reflect.KClass

/**
 * Lets the branches of a split state machine do their steps of a tick at the same time, each branch on a thread of
 * its own, on the shared model.
 *
 * The feature holds no state. The engine asks the feature manager at the start of every tick whether this feature is
 * switched on and steps the branches of every split state machine of that tick either in parallel, through its
 * `ParallelBranchStepper`, or one after the other on the engine thread as without the feature. A switch in the middle
 * of a run therefore takes effect at the next tick boundary, and the threads of the branches end at the end of the
 * tick in which the feature was switched off. Like History and TickLimit the feature takes no messages and answers
 * every message with an empty text.
 */
class TrueParallelFeature : DefaultFeature()

/** Registers [TrueParallelFeature] under the name `TrueParallel`; it needs no other feature. */
@AutoService(FeatureProvider::class)
class TrueParallelProvider : FeatureProvider {
    override val registryName = "TrueParallel"
    override val registryClass = TrueParallelFeature::class
    override val featureDependencies: Set<KClass<out Feature>> = emptySet()

    override fun createFeature(manager: FeatureManager): Feature {
        return TrueParallelFeature()
    }
}
