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

import io.karpfen.io.karpfen.exec.PythonSession
import io.karpfen.io.karpfen.exec.PythonSessionPool
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers [PythonSessionPool] with sessions that answer with their own number and never start Python: calls one after
 * the other share a session, calls at the same time get one each, and closing ends every session.
 */
class PythonSessionPoolTest {

    /** Numbers the sessions in the order the pool creates them. */
    private val created = AtomicInteger()

    /** The sessions the pool created, in order. */
    private val sessions = ArrayList<FakeSession>()

    private val pool = PythonSessionPool { FakeSession(created.incrementAndGet()).also { synchronized(sessions) { sessions += it } } }

    @Test
    fun `calls one after the other share one session`() {
        assertEquals("1", pool.execute("first"))
        assertEquals("1", pool.execute("second"))
        assertEquals(1, pool.size)
    }

    @Test
    fun `calls at the same time get a session each`() {
        val bothInside = CountDownLatch(2)
        val answers = arrayOfNulls<String>(2)
        val callers = (0..1).map { i ->
            thread { answers[i] = pool.execute("wait") { bothInside.countDown(); bothInside.await(WAIT_MS, TimeUnit.MILLISECONDS) } }
        }
        callers.forEach { it.join(WAIT_MS) }

        assertEquals(0, bothInside.count)
        assertNotEquals(answers[0], answers[1])
        assertEquals(2, pool.size)
    }

    @Test
    fun `closing ends every session and refuses later calls`() {
        pool.execute("one")
        pool.close()

        assertTrue(sessions.all { it.closed })
        assertEquals(0, pool.size)
        assertFailsWith<IllegalStateException> { pool.execute("late") }
    }

    /** Runs [code] in the pool while [inside] runs on the session; [inside] stands for Python that takes its time. */
    private fun PythonSessionPool.execute(code: String, inside: () -> Unit): String? {
        FakeSession.inside.set(inside)
        try {
            return execute(code)
        } finally {
            FakeSession.inside.remove()
        }
    }

    /** A session that answers every call with its [number]. */
    private class FakeSession(val number: Int) : PythonSession {
        @Volatile
        var closed = false

        override fun execute(code: String): String {
            inside.get()?.invoke()
            return number.toString()
        }

        override fun close() {
            closed = true
        }

        companion object {
            /** What the session does during a call of the calling thread, standing for the time Python takes. */
            val inside = ThreadLocal<(() -> Unit)?>()
        }
    }

    private companion object {
        /** Generous deadline for every wait of these tests. */
        const val WAIT_MS = 10_000L
    }
}
