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

import java.util.concurrent.atomic.AtomicLong

/**
 * Counts the observations the engine of one activated environment produces: every object change, every object
 * deletion and every change of a machine's active stack.
 *
 * The counter starts at 0, so the first observation gets revision 1. Revisions are assigned on the engine thread
 * inside a tick, in the call that enqueues the push messages, so per environment they increase strictly in enqueue
 * order and every recipient of one observation gets the same revision. A snapshot read between ticks carries
 * [current], the revision of the last observation it contains. The counter lives as long as the activation, so a
 * restart after a stop continues counting.
 */
class ChangeRevision {

    /** The revision of the last observation; read by snapshots on other threads. */
    private val last = AtomicLong(0L)

    /** Assigns the next revision to a new observation and returns it. */
    fun next(): Long = last.incrementAndGet()

    /** The revision of the last observation, or 0 before anything was observed. */
    fun current(): Long = last.get()
}
