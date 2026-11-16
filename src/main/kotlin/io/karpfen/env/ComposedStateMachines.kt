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

import io.karpfen.dsl.composition.FlatteningResult
import io.karpfen.dsl.composition.StateMachineFlattener
import io.karpfen.dsl.states.StateMachine

/**
 * The state machines an activated environment runs: every attached state machine and every attachable definition of
 * the [Environment], each flattened with the fragments of the environment through [StateMachineFlattener].
 *
 * A flattened state machine has no container state left to resolve. Each container state holds a copy of the states of
 * its fragment, named `<container>/<state>`, and the engine only ever sees these flat state machines. The texts the
 * environment keeps are the uploaded ones; only the engine runs on the flat copies.
 *
 * @property attached the flat attached state machines by the key of their object, in the order of the environment.
 * @property definitions the flat attachable state machines by their name, in the order of the environment.
 */
class ComposedStateMachines private constructor(
    val attached: Map<String, StateMachine>,
    val definitions: Map<String, StateMachine>
) {

    companion object {

        /**
         * Flattens every attached state machine and every attachable definition of [environment] with its fragments.
         * The fragments are found by name among the fragments of the environment; the attachable definitions are
         * offered as well, so that a container state that uses one is reported as using no fragment.
         *
         * @throws IllegalStateException when a state machine does not flatten. The message names the environment and
         * has one line per problem, each naming the state machine of the environment it belongs to and the problem as
         * dsl-tools words it, for example the container state, the state machine and the missing name. The
         * environment is not changed.
         */
        fun of(environment: Environment): ComposedStateMachines {
            val available = environment.stateMachineFragments.values + environment.stateMachineDefinitions.values
            val problems = mutableListOf<String>()
            fun flat(label: String, machine: StateMachine): StateMachine? =
                when (val result = StateMachineFlattener.flatten(machine, available)) {
                    is FlatteningResult.Flattened -> result.machine
                    is FlatteningResult.NotFlattened -> {
                        result.problems.forEach { problems.add("$label: ${it.message}") }
                        null
                    }
                }

            val attached = environment.stateMachines.mapValues { (key, machine) ->
                flat("State machine for '$key'", machine)
            }
            val definitions = environment.stateMachineDefinitions.mapValues { (name, machine) ->
                flat("Attachable state machine '$name'", machine)
            }
            if (problems.isNotEmpty()) {
                throw IllegalStateException(
                    "Environment with key ${environment.key} has state machines that cannot be flattened:\n" +
                        problems.joinToString("\n")
                )
            }
            return ComposedStateMachines(attached.mapValues { it.value!! }, definitions.mapValues { it.value!! })
        }
    }
}
