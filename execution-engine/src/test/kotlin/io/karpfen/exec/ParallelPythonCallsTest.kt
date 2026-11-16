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
package io.karpfen.exec

import io.karpfen.io.karpfen.exec.MacroProcessor
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.assertTrue

/**
 * Calls the persistent Python sessions of one [MacroProcessor] from several threads at once, as the branches of a split
 * state machine do with the feature TrueParallel. Every call must get its own answer, never one of another thread.
 */
@Tag("python")
class ParallelPythonCallsTest {

    private val processor = run {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        MacroProcessor(metamodel, model, emptyList(), "turtle", ModelQueryProcessor(metamodel, model))
    }

    @AfterEach
    fun close() {
        processor.pythonSessions.close()
    }

    @Test
    fun `calls from several threads at once each get their own answer`() {
        val wrong = ConcurrentLinkedQueue<String>()
        val callers = (1..4).map { caller ->
            thread {
                repeat(20) { call ->
                    val expected = "$caller-$call"
                    val answer = processor.runPython("import math\n__karpfen_result__ = '$expected'")
                    if (answer != expected) wrong += "expected $expected, got $answer"
                }
            }
        }
        callers.forEach { it.join(60_000) }

        assertTrue(wrong.isEmpty(), "wrong answers: $wrong")
    }
}
