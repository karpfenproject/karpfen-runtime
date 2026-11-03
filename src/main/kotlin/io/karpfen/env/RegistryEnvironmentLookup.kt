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
package io.karpfen.env

import instance.Model
import io.karpfen.observation.EnvironmentLookup
import io.karpfen.observation.ObservedEnvironment

/**
 * Finds environments for the observation feature in the runtime's registry, [EnvironmentHandler]: created
 * environments in its environment map, activated ones by their execution thread. It holds no state of its own.
 */
object RegistryEnvironmentLookup : EnvironmentLookup {

    /** True when [envKey] is in the registry's environment map. */
    override fun exists(envKey: String): Boolean = EnvironmentHandler.envs.containsKey(envKey)

    /** The model currently uploaded to [envKey], if any. */
    override fun uploadedModel(envKey: String): Model? = EnvironmentHandler.envs[envKey]?.model

    /** The execution thread of [envKey], which exists from activation on. */
    override fun observed(envKey: String): ObservedEnvironment? = EnvironmentHandler.executionThreads[envKey]
}
