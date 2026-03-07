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

import states.State
import states.StateMachine

class StateMachineQueryHelper(val stateMachine: StateMachine) {

    /**
     * Finds the initial state stack by recursively traversing states.
     * The stack is ordered from outermost state to innermost state.
     * At each level, the initial state is the one marked with [State.isInitial].
     * If no state is marked initial, the first state at that level is used.
     */
    fun getInitialStateStack(): List<String> {
        val stack = mutableListOf<String>()
        findInitialStateRecursive(stateMachine.states, stack)
        return stack
    }

    private fun findInitialStateRecursive(states: List<State>, stack: MutableList<String>) {
        if (states.isEmpty()) return
        val initialState = states.firstOrNull { it.isInitial } ?: states.first()
        stack.add(initialState.name)
        if (initialState.innerStates.isNotEmpty()) {
            findInitialStateRecursive(initialState.innerStates, stack)
        }
    }

    /**
     * Returns the full state stack (path from root to the named state) for a given state name.
     * Searches recursively through the entire state tree.
     *
     * @return A list of state names from outermost to innermost, ending with [stateName],
     *         or an empty list if the state is not found.
     */
    fun getStateStackForState(stateName: String): List<String> {
        val path = mutableListOf<String>()
        if (findStatePath(stateMachine.states, stateName, path)) {
            return path
        }
        return emptyList()
    }

    private fun findStatePath(states: List<State>, target: String, path: MutableList<String>): Boolean {
        for (state in states) {
            path.add(state.name)
            if (state.name == target) {
                return true
            }
            if (findStatePath(state.innerStates, target, path)) {
                return true
            }
            path.removeAt(path.lastIndex)
        }
        return false
    }

    /**
     * Returns all states reachable from the given state via a single transition.
     * Also considers transitions from any state in the current state's stack (i.e., parent states),
     * since a transition from a parent state can also fire while in a child state.
     *
     * @return A list of target state names reachable via transitions.
     */
    fun getStatesReachableFromState(stateName: String): List<String> {
        val currentStack = getStateStackForState(stateName)
        val reachable = mutableSetOf<String>()
        for (transition in stateMachine.transitions) {
            if (currentStack.contains(transition.fromState)) {
                reachable.add(transition.toState)
            }
        }
        return reachable.toList()
    }

    /**
     * Finds a [State] object by name, searching recursively.
     */
    fun findStateByName(stateName: String): State? {
        return findStateByNameRecursive(stateMachine.states, stateName)
    }

    private fun findStateByNameRecursive(states: List<State>, name: String): State? {
        for (state in states) {
            if (state.name == name) return state
            val found = findStateByNameRecursive(state.innerStates, name)
            if (found != null) return found
        }
        return null
    }
}