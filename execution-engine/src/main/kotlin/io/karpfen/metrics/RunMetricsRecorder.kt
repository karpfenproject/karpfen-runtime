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
package io.karpfen.io.karpfen.metrics

/**
 * The ways the code of an `EVAL` block or a macro runs.
 *
 * @property wireName the name of the way in the answer of the metrics route.
 */
enum class CodeWay(val wireName: String) {

    /** The runtime computes the code itself, without Python. */
    COMPUTED("computed"),

    /** A new Python process runs the code and ends. */
    NEW_PROCESS("newProcess"),

    /** The Python process that stays open for the state machine instance runs the code. */
    OPEN_PROCESS("openProcess"),

    /** A stored result of the same code with the same values answers the call; no code runs. */
    CACHE("cache");

    /** True for the ways in which the call waits for Python. */
    val waitsForPython: Boolean
        get() = this == NEW_PROCESS || this == OPEN_PROCESS
}

/**
 * Where the engine and the code it runs report what they measure. The engine hands one recorder to the places where the
 * facts arise: its tick loop reports every tick, and every macro processor reports every call of code. Durations are
 * differences of [System.nanoTime].
 *
 * Implementations must be safe to call from several threads at once, since the branches of a split state machine run
 * code on threads of their own while the feature TrueParallel is on, and cheap enough to be called on every tick and
 * every call.
 */
interface RunMetricsRecorder {

    /** Records a tick of the engine that took [durationNanos]. Called on the engine thread at the end of each tick. */
    fun tickEnded(durationNanos: Long)

    /** Records one call of code that ran in the way [way] and took [durationNanos], failed calls included. */
    fun codeRan(way: CodeWay, durationNanos: Long)

    companion object {
        /** A recorder that records nothing, for macro processors that run outside an engine. */
        val NONE: RunMetricsRecorder = object : RunMetricsRecorder {
            override fun tickEnded(durationNanos: Long) = Unit
            override fun codeRan(way: CodeWay, durationNanos: Long) = Unit
        }
    }
}
