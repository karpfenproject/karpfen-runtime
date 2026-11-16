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

/**
 * The protocol of the Python process that stays open: the result comes from the global `__karpfen_result__`, what the
 * code prints comes back apart from it, every call runs in fresh globals while loaded modules stay, and an error is part
 * of the answer and keeps the process.
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
}
