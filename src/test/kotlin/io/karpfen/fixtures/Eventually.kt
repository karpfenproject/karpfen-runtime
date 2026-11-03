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
package io.karpfen.fixtures

import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * Waits for conditions that a running engine makes true at its own pace. No test asserts a duration: each wait polls
 * with a generous deadline and fails naming what it waited for.
 */
object Eventually {

    /** The generous deadline of every wait, in milliseconds. */
    const val DEADLINE_MS = 10_000L

    /** Pause between two polls of a condition, in milliseconds. */
    private const val POLL_MS = 10L

    /** Polls [condition] until it holds, failing with [description] when the deadline passes first. */
    fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting until $description")
            Thread.sleep(POLL_MS)
        }
    }
}
