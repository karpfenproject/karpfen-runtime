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

object StateStackHelper {

    /**
     * Computes the sequence of states that need to be entered when transitioning
     * from [oldStack] to [newStack].
     *
     * This finds the longest common prefix of both stacks and returns the new
     * states that are not part of that prefix.
     *
     * Example: oldStack = [A, B, C, D], newStack = [A, B, E, F]
     * → common prefix = [A, B], changed sequence = [E, F]
     *
     * If the newStack is entirely different, all states of newStack are returned.
     * If both stacks are identical, an empty list is returned.
     */
    fun getChangedStackSequence(oldStack: List<String>, newStack: List<String>): List<String> {
        var commonLength = 0
        val minLength = minOf(oldStack.size, newStack.size)
        for (i in 0 until minLength) {
            if (oldStack[i] == newStack[i]) {
                commonLength++
            } else {
                break
            }
        }
        return newStack.subList(commonLength, newStack.size)
    }

}