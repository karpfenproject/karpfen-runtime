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

/**
 * Learns from an engine when a state machine instance appears on an object and when it leaves.
 *
 * Both are called on the engine thread inside a tick, or inside the tick gate right before the first tick of a start,
 * so a reader between ticks sees them together with the model they belong to. One object has at most one instance at
 * a time, so the key of the object names the instance. For an `EXCHANGE` the instance that leaves is reported before
 * the one that takes its place.
 */
interface StateMachineObserver {

    /**
     * An instance of the state machine [definitionName], or of the state machine attached by the run configuration when
     * it is null, appeared on the object [objectId]. It reports its first stack after its first step.
     */
    fun onStateMachineAttached(objectId: String, definitionName: String?)

    /** The instance on the object [objectId] left; it does no further step and reports no further stack. */
    fun onStateMachineDetached(objectId: String, definitionName: String?)
}
