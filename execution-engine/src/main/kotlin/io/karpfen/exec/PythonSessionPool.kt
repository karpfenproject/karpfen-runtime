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
 * A Python process that runs one piece of code at a time and stays open between calls.
 */
interface PythonSession {

    /** Runs [code] and returns what it printed, trimmed, or null when it printed nothing or failed in Python. */
    fun execute(code: String): String?

    /** Ends the process. */
    fun close()

    /**
     * Ends the process at once, also while another thread waits in [execute] for its answer; that call then fails.
     * Called only when the engine ends for good.
     */
    fun abort() = close()
}

/**
 * Hands each call a [PythonSession] that no other call uses at the same time.
 *
 * A call borrows an idle session, or a new one from [newSession] when every session is busy, and gives it back when it
 * ends. Calls that come one after the other therefore share one session, and the pool grows only to the number of
 * calls that run at the same time, which is the number of branches of a split state machine that step on threads of
 * their own. Sessions are reused across ticks, splits and joins, so a state machine that splits again and again does
 * not start a new Python process each time.
 *
 * [close] ends every session. A session that is still in use is closed when its call gives it back, and a call after
 * [close] fails, so no Python process outlives the engine that closed the pool.
 *
 * @property newSession creates a session; the session may start its process lazily on its first call.
 */
class PythonSessionPool(private val newSession: () -> PythonSession) {

    /** The sessions that no call uses now, the most recently used last. Guarded by this pool. */
    private val idle = ArrayDeque<PythonSession>()

    /** True once [close] ran. Guarded by this pool. */
    private var closed = false

    /** The number of sessions this pool created and has not closed. Guarded by this pool. */
    private var created = 0

    /** The sessions that calls use now. Guarded by this pool. */
    private val busy = HashSet<PythonSession>()

    /** The number of sessions this pool created and has not closed. */
    val size: Int
        @Synchronized get() = created

    /**
     * Runs [code] in a session that no other call uses meanwhile and returns its output.
     *
     * @throws IllegalStateException when the pool is closed.
     */
    fun execute(code: String): String? {
        val session = borrow()
        try {
            return session.execute(code)
        } finally {
            giveBack(session)
        }
    }

    /** Ends every session; idle ones at once, busy ones when their call gives them back. */
    fun close() {
        val toClose = synchronized(this) {
            closed = true
            created -= idle.size
            idle.toList().also { idle.clear() }
        }
        toClose.forEach { it.close() }
    }

    /**
     * Ends every session at once, the busy ones too, so a call that waits for Python fails instead of holding up the
     * end of the engine; used when the engine ends for good and a tick does not end on its own.
     */
    fun abort() {
        val toAbort = synchronized(this) {
            closed = true
            created -= idle.size
            (idle.toList() + busy).also { idle.clear() }
        }
        toAbort.forEach { it.abort() }
    }

    /** Takes the most recently used idle session, or creates one. */
    @Synchronized
    private fun borrow(): PythonSession {
        check(!closed) { "The Python sessions of this state machine are closed" }
        val session = idle.removeLastOrNull() ?: newSession().also { created++ }
        busy += session
        return session
    }

    /** Puts [session] back for the next call, or closes it when the pool was closed meanwhile. */
    private fun giveBack(session: PythonSession) {
        val keep = synchronized(this) {
            busy -= session
            if (!closed) idle.addLast(session) else created--
            !closed
        }
        if (!keep) session.close()
    }
}
