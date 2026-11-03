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

import instance.Model

/**
 * Where the observation feature finds environments. It is the one seam between this package and the runtime's
 * environment registry, so the observation package never depends on the registry and its services are tested with
 * fakes.
 */
interface EnvironmentLookup {

    /** True when an environment with [envKey] was created, in any phase. */
    fun exists(envKey: String): Boolean

    /** The model uploaded to [envKey] so far, or `null` when none was uploaded or the environment does not exist. */
    fun uploadedModel(envKey: String): Model?

    /** The activated environment [envKey], or `null` while it has no execution thread or does not exist. */
    fun observed(envKey: String): ObservedEnvironment?
}
