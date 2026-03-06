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

import states.StateMachine

class StateMachineQueryHelper(val stateMachine: StateMachine) {

    fun getInitialStateStack(): List<String> {
        //TODO find initial state and its stack
        return listOf()
    }

    fun getStateStackForState(stateName: String): List<String> {
        //TODO find the state stack for the given state
        return listOf()
    }

    fun getStatesReachableFromState(stateName: String): List<String> {
        //TODO find all states reachable from the given state. Reachable means connected via a single transition,
        // but the transition does not necessarily have to be executable right now.
        //  make sure to include states reachable from states of the current state stack as well
        return listOf()
    }

}