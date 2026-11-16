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

import io.karpfen.dsl.states.History
import io.karpfen.dsl.states.InitialStates
import io.karpfen.dsl.states.JoinTransition
import io.karpfen.dsl.states.SplitTransition
import io.karpfen.dsl.states.State
import io.karpfen.dsl.states.StateMachine
import io.karpfen.dsl.states.Transition

/**
 * Answers questions about the states and transitions of [stateMachine], whose states must not change while the
 * helper is in use.
 *
 * The states by name and the path from the outermost state to each state are indexed on first use, because the engine
 * asks for them several times per tick. When two states share a name, the index keeps the first in the order of a
 * depth-first walk of the state tree, the state a search from the top would find.
 */
class StateMachineQueryHelper(val stateMachine: StateMachine) {

    /** The paths of all states by name, outermost state first, each ending with the named state. */
    private val pathsByName: Map<String, List<String>> by lazy {
        val paths = HashMap<String, List<String>>()
        indexPaths(stateMachine.states, emptyList(), paths)
        paths
    }

    /** The states by name, the first of the walk when names repeat. */
    private val statesByName: Map<String, State> by lazy {
        val states = HashMap<String, State>()
        indexStates(stateMachine.states, states)
        states
    }

    private fun indexPaths(states: List<State>, above: List<String>, into: MutableMap<String, List<String>>) {
        for (state in states) {
            val path = above + state.name
            into.putIfAbsent(state.name, path)
            indexPaths(state.innerStates, path, into)
        }
    }

    private fun indexStates(states: List<State>, into: MutableMap<String, State>) {
        for (state in states) {
            into.putIfAbsent(state.name, state)
            indexStates(state.innerStates, into)
        }
    }

    /**
     * Returns the stack an instance starts in, outermost first: the start path of [InitialStates.startPath], the rule
     * that the language checks and the IDE use as well. At each level it takes the state that is marked `INITIAL` or
     * holds the mark below it without passing through a container state, otherwise the first state, and goes on below
     * it while it has inner states. A marked state inside a later sibling therefore starts the instance in it and in
     * every state around it. In a flattened state machine the path goes on through container states, so an instance
     * never starts on a container state alone.
     */
    fun getInitialStateStack(): List<String> = InitialStates.startPath(stateMachine).map { it.name }

    /**
     * Returns the stack a branch rests on when it enters the states of [stack], outermost first, the last of them being
     * the target of the entry. When that target is a container state, the branch goes down into it: to the state its
     * history remembers through [remembered], when the history remembers one inside it, otherwise along
     * [InitialStates.enteredBelow], the start path of its fragment. Each state on the way down that has a history which
     * remembers a state takes the branch there instead, and a container state reached through a history is entered the
     * same way. Any other target, a compound state included, is entered as itself and [stack] is returned unchanged.
     *
     * @param remembered the state the history of a state remembers, or null when it has no history or no memory.
     */
    fun getEnteredStack(stack: List<String>, remembered: (State) -> String? = { null }): List<String> {
        val target = stack.lastOrNull()?.let(::findStateByName) ?: return stack
        return if (target.isContainer) goDown(stack, target, remembered) else stack
    }

    /**
     * Returns [stack] extended below its last state [state], which has just been entered on the way into a container
     * state. A remembered state below [state] wins; a container state reached that way is gone down into again.
     */
    private fun goDown(stack: List<String>, state: State, remembered: (State) -> String?): List<String> {
        val memory = remembered(state)?.let(::getStateStackForState)
            ?.takeIf { it.size > stack.size && it.subList(0, stack.size) == stack }
        if (memory != null) {
            val reached = findStateByName(memory.last()) ?: return memory
            return if (reached.isContainer) goDown(memory, reached, remembered) else memory
        }
        val result = stack.toMutableList()
        for (inner in InitialStates.enteredBelow(state)) {
            result.add(inner.name)
            if (inner.history != History.NONE && remembered(inner) != null) return goDown(result, inner, remembered)
        }
        return result
    }

    /**
     * Returns true when the container state [container] has reached a final state in [stack], a branch's stack: the
     * state directly below [container] in [stack] is a final state of its fragment. This is what the condition `DONE`
     * asks about a container state. False when [container] is not a container state or is not active in [stack].
     */
    fun hasReachedFinalState(container: String, stack: List<String>): Boolean {
        if (findStateByName(container)?.isContainer != true) return false
        val index = stack.indexOf(container)
        if (index < 0 || index + 1 >= stack.size) return false
        return findStateByName(stack[index + 1])?.isFinal == true
    }

    /**
     * Returns the full state stack (path from root to the named state) for a given state name.
     *
     * @return A list of state names from outermost to innermost, ending with [stateName],
     *         or an empty list if the state is not found.
     */
    fun getStateStackForState(stateName: String): List<String> =
        pathsByName[stateName]?.let(::ArrayList) ?: emptyList()

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
            when (transition) {
                is Transition -> if (currentStack.contains(transition.fromState)) reachable.add(transition.toState)
                is SplitTransition -> if (currentStack.contains(transition.fromState)) reachable.addAll(transition.toStates)
                is JoinTransition -> if (transition.fromStates.any { currentStack.contains(it) }) reachable.add(transition.toState)
            }
        }
        return reachable.toList()
    }

    /**
     * Finds a [State] object by name, from the index of states.
     */
    fun findStateByName(stateName: String): State? = statesByName[stateName]
}