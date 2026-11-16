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

import io.karpfen.io.karpfen.exec.MacroCodeTransformer
import io.karpfen.io.karpfen.exec.PersistentPythonSession
import io.karpfen.io.karpfen.exec.PythonAnswer
import io.karpfen.io.karpfen.exec.PythonExecutionException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * The protocol of the Python process that stays open: the result comes from the global `__karpfen_result__`, what the
 * code prints comes back apart from it, also what it prints through `logging` or what the compiler warns about it,
 * text printed outside a call is discarded, standard input is empty, every call runs in fresh globals while loaded
 * modules stay, and an error is part of the answer and keeps the process.
 */
@Tag("python")
class PersistentPythonSessionTest {

    private val session = PersistentPythonSession()

    @AfterEach
    fun tearDown() {
        session.close()
    }

    // ---- Results ----

    @Test
    fun `the result is the value of the result global as text`() {
        assertEquals(PythonAnswer("11", ""), session.execute("__karpfen_result__ = 3 + 4 * 2"))
        assertEquals(PythonAnswer("hello", ""), session.execute("__karpfen_result__ = 'hello'"))
        assertEquals(PythonAnswer("2.5", ""), session.execute("__karpfen_result__ = 2.5"))
    }

    @Test
    fun `a dictionary, a list and a boolean come back as JSON`() {
        assertEquals("{\"x\": 1.0, \"y\": 2.0}", session.execute("__karpfen_result__ = {'x': 1.0, 'y': 2.0}").result)
        assertEquals("[1, \"a\", null]", session.execute("__karpfen_result__ = [1, 'a', None]").result)
        assertEquals("true", session.execute("__karpfen_result__ = True").result)
        assertEquals("false", session.execute("__karpfen_result__ = 1 > 2").result)
    }

    @Test
    fun `code without the result global or with None gives no result`() {
        assertEquals(PythonAnswer(null, ""), session.execute("x = 42"))
        assertEquals(PythonAnswer(null, ""), session.execute("__karpfen_result__ = None"))
    }

    @Test
    fun `an empty string and texts with line breaks come back unchanged`() {
        assertEquals("", session.execute("__karpfen_result__ = ''").result)
        assertEquals("a\nb\r\nc\n", session.execute("__karpfen_result__ = 'a\\nb\\r\\nc\\n'").result)
    }

    @Test
    fun `the code text that the transformer builds gives the value of its main function`() {
        val code = "import json\n\ndef __karpfen_main__():\n    return 5.0 + 0.3 * 1.0\n\n${MacroCodeTransformer.RESULT_LINE}\n"
        assertEquals("5.3", session.execute(code).result)
    }

    // ---- Printed text ----

    @Test
    fun `what the code prints to stdout and stderr comes back apart from the result`() {
        val answer = session.execute(
            "import sys\nprint('line1')\nprint('line2')\nsys.stderr.write('warning\\n')\n__karpfen_result__ = 7"
        )
        assertEquals(PythonAnswer("7", "line1\nline2\nwarning\n"), answer)
    }

    @Test
    fun `text printed after its call returned is discarded and leaves the next answer intact`() {
        val marker = Files.createTempFile("karpfen-late", ".txt").also { Files.delete(it) }
        val late = "import threading\n" +
            "def later():\n" +
            "    import sys\n" +
            "    print('late')\n" +
            "    sys.stderr.write('late too\\n')\n" +
            "    sys.stdout.flush()\n" +
            "    open(${pythonText(marker.toString())}, 'w').close()\n" +
            "threading.Timer(0.2, later).start()\n" +
            "__karpfen_result__ = 1"
        assertEquals(PythonAnswer("1", ""), session.execute(late))
        val pid = session.pid()

        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!Files.exists(marker)) {
            assertTrue(System.currentTimeMillis() < deadline, "the timer did not print")
            Thread.sleep(5)
        }
        Files.delete(marker)

        assertEquals(PythonAnswer("2", ""), session.execute("__karpfen_result__ = 2"))
        assertEquals(pid, session.pid())
    }

    @Test
    fun `stdout and stderr are the same objects in every call, and no stream of the loop is reachable by name`() {
        session.execute("import sys\nsys.karpfen_seen = (sys.stdout, sys.stderr)")
        assertEquals(
            "true",
            session.execute("import sys\n__karpfen_result__ = sys.karpfen_seen == (sys.stdout, sys.stderr)").result
        )
        val streams = "import __main__, io\n" +
            "__karpfen_result__ = [k for k, v in vars(__main__).items() if isinstance(v, io.IOBase)]"
        assertEquals("[]", session.execute(streams).result)
    }

    @Test
    fun `what logging writes reaches the call that logs, in every call`() {
        assertEquals("WARNING:root:one\n", session.execute("import logging\nlogging.warning('one')").printed)
        assertEquals("WARNING:root:two\n", session.execute("import logging\nlogging.warning('two')").printed)
    }

    @Test
    fun `a warning of the compiler reaches the call whose code it is about`() {
        val printed = session.execute("x = 1\n__karpfen_result__ = x is 1").printed
        assertTrue(printed.contains("SyntaxWarning"), printed)
    }

    @Test
    fun `code and its child processes read an empty standard input`() {
        val answers = runBounded {
            listOf(
                session.execute("__karpfen_result__ = input()"),
                session.execute(
                    "import subprocess, sys\n" +
                        "child = subprocess.run([sys.executable, '-c', 'import sys; print(len(sys.stdin.read()))'], " +
                        "capture_output=True, text=True)\n" +
                        "__karpfen_result__ = child.stdout.strip()"
                ),
                session.execute("__karpfen_result__ = 3")
            )
        }

        assertEquals("EOFError: EOF when reading a line", answers[0].error)
        assertEquals("0", answers[1].result)
        assertEquals("3", answers[2].result)
    }

    // ---- Namespaces ----

    @Test
    fun `every call starts with fresh globals`() {
        session.execute("my_var = 42\ndef helper():\n    return 1")
        assertEquals(PythonAnswer("False False", ""), session.execute("__karpfen_result__ = f\"{'my_var' in globals()} {'helper' in globals()}\""))
        assertEquals("NameError: name 'my_var' is not defined", session.execute("__karpfen_result__ = my_var").error)
    }

    @Test
    fun `the same code text runs again in fresh globals and fails again the same way`() {
        val counting = "count = globals().get('count', 0) + 1\nprint(count)\n__karpfen_result__ = count"
        repeat(3) { assertEquals(PythonAnswer("1", "1\n"), session.execute(counting)) }

        val syntaxErrors = List(2) { session.execute("__karpfen_result__ = (1 +* 2)").error }
        assertTrue(syntaxErrors[0]!!.startsWith("SyntaxError"), syntaxErrors[0])
        assertEquals(syntaxErrors[0], syntaxErrors[1])
        repeat(2) {
            assertEquals("ZeroDivisionError: division by zero", session.execute("__karpfen_result__ = 1 / 0").error)
        }
    }

    @Test
    fun `more code texts than the loop keeps compiled still give their own results`() {
        for (round in 1..2) {
            for (i in 0 until 300) assertEquals("$i", session.execute("__karpfen_result__ = $i").result)
        }
        val long = "__karpfen_result__ = len('${"x".repeat(20_000)}')"
        repeat(2) { assertEquals("20000", session.execute(long).result) }
    }

    @Test
    fun `a loaded module stays loaded, while its name must be imported again`() {
        session.execute("import decimal")
        val error = session.execute("__karpfen_result__ = decimal.Decimal(1)").error
        assertTrue(error!!.startsWith("NameError: name 'decimal' is not defined"), error)
        assertEquals("true", session.execute("import sys\n__karpfen_result__ = 'decimal' in sys.modules").result)
    }

    // ---- Errors ----

    @Test
    fun `an error is part of the answer, with what was printed before it, and keeps the process`() {
        assertEquals(PythonAnswer("before", ""), session.execute("__karpfen_result__ = 'before'"))
        val pid = session.pid()

        assertEquals(
            PythonAnswer(null, "printed\n", "ZeroDivisionError: division by zero"),
            session.execute("print('printed')\n__karpfen_result__ = 1/0")
        )
        assertEquals(PythonAnswer(null, "", "SystemExit: 3"), session.execute("import sys\nsys.exit(3)"))
        assertTrue(session.execute("x = (").error!!.startsWith("SyntaxError: "))

        assertEquals(PythonAnswer("after", ""), session.execute("__karpfen_result__ = 'after'"))
        assertEquals(pid, session.pid())
    }

    @Test
    fun `a result that cannot be written as JSON is an error of the call`() {
        val error = session.execute("__karpfen_result__ = {'s': {1, 2}}").error
        assertEquals("TypeError: Object of type set is not JSON serializable", error)
    }

    @Test
    fun `code that replaces stdout does not break the next call`() {
        assertEquals("1", session.execute("import sys\nsys.stdout = None\n__karpfen_result__ = 1").result)
        assertEquals(PythonAnswer("2", "seen\n"), session.execute("print('seen')\n__karpfen_result__ = 2"))
    }

    @Test
    fun `output past sys stdout, also of a child process, is dropped and leaves the answer intact`() {
        val code = "import os, subprocess, sys\nos.write(1, b'raw\\n')\n" +
            "subprocess.run([sys.executable, '-c', 'print(1)'])\n__karpfen_result__ = 3"

        assertEquals(PythonAnswer("3", ""), session.execute(code))
        assertEquals("4", session.execute("__karpfen_result__ = 4").result)
    }

    @Test
    fun `a process that ends during a call fails the call and the next call starts a new one`() {
        session.execute("x = 1")
        val first = session.pid()

        val ended = assertThrows(PythonExecutionException::class.java) { session.execute("import os\nos._exit(3)") }

        assertEquals("The Python process ended during the call", ended.message)
        assertEquals("1", session.execute("__karpfen_result__ = 1").result)
        assertNotEquals(first, session.pid())
    }

    // ---- Lifecycle ----

    @Test
    fun `close ends the process and the next call starts it again`() {
        session.execute("x = 1")
        assertTrue(session.isAlive())

        session.close()
        assertFalse(session.isAlive())

        assertEquals("restarted", session.execute("__karpfen_result__ = 'restarted'").result)
        assertTrue(session.isAlive())
    }

    /** [text] as a Python string literal. */
    private fun pythonText(text: String): String = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /**
     * Runs [calls] on a thread of its own and returns what they gave. When they have not ended within [WAIT_MS], the
     * session is aborted, which ends a call that waits for Python, and the test fails.
     */
    private fun <T> runBounded(calls: () -> T): T {
        var outcome: Result<T>? = null
        val caller = thread { outcome = runCatching(calls) }
        caller.join(WAIT_MS)
        if (caller.isAlive) {
            session.abort()
            caller.join(WAIT_MS)
            fail<Unit>("the calls did not end within $WAIT_MS ms")
        }
        return outcome!!.getOrThrow()
    }

    private companion object {
        /** Generous deadline for every wait of these tests. */
        const val WAIT_MS = 10_000L
    }
}
