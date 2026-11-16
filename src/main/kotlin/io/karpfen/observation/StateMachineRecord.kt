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
 * The state machine instances of one environment as its clients were told about them, kept for model snapshots.
 *
 * The engine reports every instance that appears on an object and every instance that leaves (see
 * [ObservationRelay.onStateMachineAttached]). The record keeps, per object key, the name of the definition of its
 * instance, or null for a state machine attached by the run configuration, in the order the instances appeared. It is
 * written on the engine thread inside the tick gate and read by snapshot requests, so every member is synchronized and
 * [current] hands out an immutable copy.
 */
class StateMachineRecord {

    /** The definition name per object key, null for an attached state machine, in the order of appearance. */
    private val instances = LinkedHashMap<String, String?>()

    /** Records that an instance of [definitionName] appeared on [objectId]. */
    @Synchronized
    fun attached(objectId: String, definitionName: String?) {
        instances[objectId] = definitionName
    }

    /** Records that the instance on [objectId] left. */
    @Synchronized
    fun detached(objectId: String) {
        instances.remove(objectId)
    }

    /** An immutable copy of the recorded instances, in the order of appearance. */
    @Synchronized
    fun current(): Map<String, String?> = Collections.unmodifiableMap(LinkedHashMap(instances))

    /** Forgets every instance, as a start does before the instances of the start are reported. */
    @Synchronized
    fun clear() {
        instances.clear()
    }
}
