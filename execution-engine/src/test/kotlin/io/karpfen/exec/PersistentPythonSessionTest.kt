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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PersistentPythonSessionTest {

    private var session: PersistentPythonSession? = null

    @AfterEach
    fun tearDown() {
        session?.close()
    }

    // ---- Basic execution ----

    @Test
    fun `execute simple print`() {
        session = PersistentPythonSession()
        val result = session!!.execute("print('hello')")
        assertEquals("hello", result)
    }

    @Test
    fun `execute arithmetic`() {
        session = PersistentPythonSession()
        val result = session!!.execute("print(3 + 4 * 2)")
        assertEquals("11", result)
    }

    @Test
    fun `execute returns null for no output`() {
        session = PersistentPythonSession()
        val result = session!!.execute("x = 42")
        assertNull(result)
    }

    @Test
    fun `execute json output`() {
        session = PersistentPythonSession()
        val result = session!!.execute("""
            import json
            result = {"x": 1.0, "y": 2.0}
            print(json.dumps(result))
        """.trimIndent())
        assertNotNull(result)
        assertTrue(result!!.contains("\"x\""))
        assertTrue(result.contains("\"y\""))
    }

    // ---- Multiple sequential executions ----

    @Test
    fun `session stays alive across multiple executions`() {
        session = PersistentPythonSession()

        val r1 = session!!.execute("print(1 + 1)")
        assertEquals("2", r1)

        val r2 = session!!.execute("print(2 * 3)")
        assertEquals("6", r2)

        val r3 = session!!.execute("print('hello')")
        assertEquals("hello", r3)

        assertTrue(session!!.isAlive())
    }

    @Test
    fun `variables persist within session`() {
        session = PersistentPythonSession()

        // Define a variable in one call
        session!!.execute("my_var = 42")

        // Use it in the next call
        val result = session!!.execute("print(my_var * 2)")
        assertEquals("84", result)
    }

    // ---- Import persistence ----

    @Test
    fun `import persists across calls`() {
        session = PersistentPythonSession()

        // Import in one call
        session!!.execute("import math")

        // Use in subsequent call
        val result = session!!.execute("print(math.sqrt(16.0))")
        assertEquals("4.0", result)
    }

    @Test
    fun `import json persists`() {
        session = PersistentPythonSession()

        session!!.execute("import json")

        val result = session!!.execute("""
            data = {"key": "value", "num": 42}
            print(json.dumps(data, sort_keys=True))
        """.trimIndent())
        assertNotNull(result)
        assertTrue(result!!.contains("\"key\""))
        assertTrue(result.contains("\"num\""))
    }

    // ---- Full macro-like code execution ----

    @Test
    fun `execute karpfen-style wrapped code`() {
        session = PersistentPythonSession()

        val code = """
            import json

            def __karpfen_main__():
            	return 5.0 + 0.3 * 1.0

            __result__ = __karpfen_main__()
            if __result__ is not None:
                if isinstance(__result__, dict):
                    print(json.dumps(__result__))
                elif isinstance(__result__, list):
                    print(json.dumps(__result__))
                elif isinstance(__result__, bool):
                    print(json.dumps(__result__))
                else:
                    print(__result__)
        """.trimIndent()

        val result = session!!.execute(code)
        assertEquals("5.3", result)
    }

    @Test
    fun `execute karpfen-style boolean code`() {
        session = PersistentPythonSession()

        val code = """
            import json

            def __karpfen_main__():
            	return 100.0 >= 1.0 and 100.0 >= 1.0

            __result__ = __karpfen_main__()
            if __result__ is not None:
                if isinstance(__result__, dict):
                    print(json.dumps(__result__))
                elif isinstance(__result__, list):
                    print(json.dumps(__result__))
                elif isinstance(__result__, bool):
                    print(json.dumps(__result__))
                else:
                    print(__result__)
        """.trimIndent()

        val result = session!!.execute(code)
        assertEquals("true", result)
    }

    @Test
    fun `execute code with math import`() {
        session = PersistentPythonSession()

        val code = """
            import json
            import math

            def point_to_point_distance(p1, p2):
            	return math.sqrt((p1["x"] - p2["x"]) ** 2 + (p1["y"] - p2["y"]) ** 2)

            def __karpfen_main__():
            	return point_to_point_distance({"x": 5.0, "y": 5.0}, {"x": 2.0, "y": 3.0})

            __result__ = __karpfen_main__()
            if __result__ is not None:
                if isinstance(__result__, dict):
                    print(json.dumps(__result__))
                elif isinstance(__result__, list):
                    print(json.dumps(__result__))
                elif isinstance(__result__, bool):
                    print(json.dumps(__result__))
                else:
                    print(__result__)
        """.trimIndent()

        val result = session!!.execute(code)
        assertNotNull(result)
        val distance = result!!.toDouble()
        assertEquals(3.606, distance, 0.01)
    }

    // ---- Error handling ----

    @Test
    fun `syntax error does not kill session`() {
        session = PersistentPythonSession()

        // First call succeeds
        val r1 = session!!.execute("print('before error')")
        assertEquals("before error", r1)

        // Second call has an error - the try/except wrapper should catch it
        val r2 = session!!.execute("print(1/0)")  // ZeroDivisionError
        // Result may be null or error message depending on how the error is handled
        // The important thing is the session survives

        // Third call should still work
        val r3 = session!!.execute("print('after error')")
        assertEquals("after error", r3)
        assertTrue(session!!.isAlive())
    }

    // ---- Session lifecycle ----

    @Test
    fun `close terminates session`() {
        session = PersistentPythonSession()
        session!!.execute("print('hello')")
        assertTrue(session!!.isAlive())

        session!!.close()
        assertFalse(session!!.isAlive())
    }

    @Test
    fun `session auto-restarts after close and re-execute`() {
        session = PersistentPythonSession()

        session!!.execute("print('first')")
        session!!.close()
        assertFalse(session!!.isAlive())

        // Executing again should auto-restart the session
        val result = session!!.execute("print('restarted')")
        assertEquals("restarted", result)
        assertTrue(session!!.isAlive())
    }

    // ---- Multi-line output ----

    @Test
    fun `handles multi-line output`() {
        session = PersistentPythonSession()
        val result = session!!.execute("""
            print("line1")
            print("line2")
            print("line3")
        """.trimIndent())
        assertNotNull(result)
        assertTrue(result!!.contains("line1"))
        assertTrue(result.contains("line2"))
        assertTrue(result.contains("line3"))
    }
}
