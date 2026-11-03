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

/**
 * An activated environment as the observation feature sees it: its lifecycle phase and a snapshot of its model read
 * between two ticks. The environment's execution thread implements it.
 */
interface ObservedEnvironment {

    /** The environment's current phase; never [EnvironmentPhase.CONFIGURING], which has no execution thread. */
    val phase: EnvironmentPhase

    /**
     * Reads the whole model, the revision and the active stacks at the next tick boundary, waiting at most
     * [timeoutMs] milliseconds for a running tick to end; without a running tick the read happens at once.
     *
     * @return the snapshot, or `null` when no tick boundary came within the timeout.
     * @throws IllegalStateException when the model holds a reference loop, which has no JSON.
     */
    fun snapshotBetweenTicks(timeoutMs: Long): ModelSnapshot?
}
