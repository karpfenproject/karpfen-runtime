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

    fun getChangedStackSequence(oldStack: List<String>, newStack: List<String>): List<String> {
        //TODO find the sequence of states that need to be entered when changing from the old stack to the new stack
        //  for example, if the old stack is [A, B, C, D] and the new stack is [A, B, E, F], then the changed stack sequence would be [E, F]
        return listOf()
    }

}