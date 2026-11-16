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
 * What the actions of a state machine may ask of the life cycle of state machine instances: the `ATTACH` clause and
 * the rule `EXCHANGE`.
 *
 * The [ActionProcessor] checks a name with [requireAttachable] before it changes anything, so a rule whose name fails
 * changes nothing. [attach] and [exchange] are requests. They may come from the engine thread or, with the feature
 * TrueParallel on, from the threads of several branches at once; the implementation collects them and changes the
 * order of the state machines only at the end of the tick (see [StateMachineLifecycle]).
 */
interface StateMachineAttachments {

    /**
     * Checks that an attachable state machine named [name] exists and lists the type [typeName].
     *
     * @throws IllegalArgumentException with the reason when it does not.
     */
    fun requireAttachable(name: String, typeName: String)

    /**
     * Gives the object [objectId], which the calling rule has just created, an instance of the attachable state machine
     * [name] in place of the one its type behaves after. The instance joins at the end of the tick.
     */
    fun attach(objectId: String, name: String)

    /**
     * Ends the instance of the object [objectId], if it has one, and gives the object an instance of the attachable
     * state machine [name] at the same place in the order, or at the end of the order when it had none. The instance
     * joins at the end of the tick.
     */
    fun exchange(objectId: String, name: String)

    /** Holds no attachable state machine; every name is unknown. For action processors outside an engine. */
    object None : StateMachineAttachments {
        override fun requireAttachable(name: String, typeName: String) {
            throw IllegalArgumentException("No attachable state machine is named '$name'")
        }

        override fun attach(objectId: String, name: String) = requireAttachable(name, "")

        override fun exchange(objectId: String, name: String) = requireAttachable(name, "")
    }
}
