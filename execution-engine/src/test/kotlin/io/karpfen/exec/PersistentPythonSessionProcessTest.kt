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

import io.karpfen.io.karpfen.exec.PersistentPythonSession
import io.karpfen.io.karpfen.exec.PythonAnswer
import io.karpfen.io.karpfen.exec.PythonExecutionException
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * How [PersistentPythonSession] handles its process on the Kotlin side, with processes that answer from fixed text and
 * never start Python: a call that does not end normally leaves no process that could answer a later call out of step,
 * and an aborted session starts no process any more.
 */
class PersistentPythonSessionProcessTest {

    /** The processes the session started, in order. */
    private val started = ArrayList<FixedProcess>()

    /** Starts the next of [answers] as a process each time the session starts one. */
    private fun session(vararg answers: () -> InputStream): PersistentPythonSession {
        val queue = ArrayDeque(answers.toList())
        return PersistentPythonSession { FixedProcess(queue.removeFirst()()).also { started += it } }
    }

    @Test
    fun `a call that fails with an Error in the middle of an answer ends its process`() {
        val session = session({ BrokenAnswer("__SENTINEL__OK\n") }, { answer("2") })

        assertFailsWith<OutOfMemoryError> { session.execute("first") }

        assertTrue(started.single().destroyed, "the process of the failed call is ended")
        assertEquals(PythonAnswer("2", ""), session.execute("second"))
        assertEquals(2, started.size, "the next call starts a new process")
    }

    @Test
    fun `an aborted session starts no process`() {
        val session = session({ answer("1") })

        session.abort()

        assertFailsWith<PythonExecutionException> { session.execute("late") }
        assertEquals(0, started.size)
    }

    @Test
    fun `an aborted session ends its process and refuses later calls`() {
        val session = session({ answer("1") }, { answer("2") })
        assertEquals("1", session.execute("first").result)

        session.abort()

        assertTrue(started.single().destroyed)
        assertFailsWith<PythonExecutionException> { session.execute("late") }
        assertEquals(1, started.size)
    }

    /** The three protocol lines of a call that left [result] and printed nothing. */
    private fun answer(result: String): InputStream {
        val encoded = Base64.getEncoder().encodeToString(result.toByteArray())
        return "__SENTINEL__OK\n$encoded\n\n".byteInputStream()
    }

    /** Gives [text] on its first read and fails every later read with an [OutOfMemoryError]. */
    private class BrokenAnswer(text: String) : InputStream() {
        private var bytes: ByteArray? = text.toByteArray()

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val first = bytes ?: throw OutOfMemoryError("while reading the answer")
            bytes = null
            first.copyInto(b, off)
            return first.size
        }
    }

    /** A process whose stdout is [output] and which runs until it is destroyed. */
    private class FixedProcess(private val output: InputStream) : Process() {
        @Volatile
        var destroyed = false

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = output
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (destroyed) 0 else throw IllegalThreadStateException()
        override fun isAlive(): Boolean = !destroyed
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process = also { destroyed = true }
    }
}
