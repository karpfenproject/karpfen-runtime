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
 * Hands out the path numbers of the branches of one engine, counted per key of an object.
 *
 * A path is one stretch of a state machine on an object that a single branch runs from its start to its end. The first
 * branch of the first instance on an object gets path 0, and every branch built for that object afterwards gets the
 * next number: each target of a split, the branch a join starts, the branch a forced state starts and the first branch
 * of every later instance on the same object, after an exchange or a new attachment. The numbers live as long as the
 * engine, through a stop and a start, and are never handed out twice for one key, so the key of an object and a path
 * number name one branch for the whole life of the engine.
 *
 * The trace log names the branch of an entry by its number, and the identity under which the branch consumes events
 * contains it as well; both read it from [Branch.path], so they cannot disagree. Safe to use from any thread.
 */
class PathNumbers {

    /** The next number for each key of an object. */
    private val next = HashMap<String, Int>()

    /** Returns the next path number for the object [objectId], starting at 0. */
    @Synchronized
    fun next(objectId: String): Int {
        val number = next[objectId] ?: 0
        next[objectId] = number + 1
        return number
    }
}
