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

import io.karpfen.io.karpfen.exec.PythonIndentationRewriter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PythonIndentationRewriterTest {

    // ---- Basic single-line cases ----

    @Test
    fun `plain return line gets no indentation at baseDepth 0`() {
        val result = PythonIndentationRewriter.rewrite("return 42")
        assertEquals("return 42", result)
    }

    @Test
    fun `plain line gets base indentation at baseDepth 1`() {
        val result = PythonIndentationRewriter.rewrite("return 42", baseDepth = 1)
        assertEquals("\treturn 42", result)
    }

    @Test
    fun `blank lines are preserved`() {
        val code = "x = 1\n\nreturn x"
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 1)
        val lines = result.lines()
        assertEquals(3, lines.size)
        assertEquals("\tx = 1", lines[0])
        assertEquals("", lines[1])
        assertEquals("\treturn x", lines[2])
    }

    // ---- if / endif ----

    @Test
    fun `if block is correctly indented with endif`() {
        val code = """
            if x > 0:
                do_something()
            endif
            return x
        """.trimIndent()
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 0)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("if x > 0:", lines[0])
        assertEquals("\tdo_something()", lines[1])
        assertEquals("return x", lines[2])
        // endif should not appear in output
        assertFalse(result.contains("endif"))
    }

    @Test
    fun `if-else block with endif`() {
        val code = """
            if x > 0:
                a = 1
            else:
                a = 2
            endif
            return a
        """.trimIndent()
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 0)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("if x > 0:", lines[0])
        assertEquals("\ta = 1", lines[1])
        assertEquals("else:", lines[2])
        assertEquals("\ta = 2", lines[3])
        assertEquals("return a", lines[4])
        assertFalse(result.contains("endif"))
    }

    @Test
    fun `elif chain with endif`() {
        val code = """
            if x > 0:
                a = 1
            elif x == 0:
                a = 0
            else:
                a = -1
            endif
            return a
        """.trimIndent()
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 0)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("if x > 0:", lines[0])
        assertEquals("\ta = 1", lines[1])
        assertEquals("elif x == 0:", lines[2])
        assertEquals("\ta = 0", lines[3])
        assertEquals("else:", lines[4])
        assertEquals("\ta = -1", lines[5])
        assertEquals("return a", lines[6])
    }

    // ---- for / endfor ----

    @Test
    fun `for loop is correctly indented with endfor`() {
        val code = """
            closest = None
            for item in items:
                closest = item
            endfor
            return closest
        """.trimIndent()
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 0)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("closest = None", lines[0])
        assertEquals("for item in items:", lines[1])
        assertEquals("\tclosest = item", lines[2])
        assertEquals("return closest", lines[3])
        assertFalse(result.contains("endfor"))
    }

    @Test
    fun `nested for with if inside`() {
        val code = """
            result = 0
            for x in xs:
                if x > 0:
                    result = result + x
                endif
            endfor
            return result
        """.trimIndent()
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 0)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("result = 0", lines[0])
        assertEquals("for x in xs:", lines[1])
        assertEquals("\tif x > 0:", lines[2])
        assertEquals("\t\tresult = result + x", lines[3])
        assertEquals("return result", lines[4])
    }

    // ---- while / endwhile ----

    @Test
    fun `while loop with endwhile`() {
        val code = """
            i = 0
            while i < 10:
                i = i + 1
            endwhile
            return i
        """.trimIndent()
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 0)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("i = 0", lines[0])
        assertEquals("while i < 10:", lines[1])
        assertEquals("\ti = i + 1", lines[2])
        assertEquals("return i", lines[3])
        assertFalse(result.contains("endwhile"))
    }

    // ---- Original indentation is ignored ----

    @Test
    fun `massive original indentation is stripped and recomputed`() {
        // Simulate deeply nested code from a .kstates file
        val code = """
                        closest_distance = 100.0
                        closest = None
                        for item in items :
                            if something :
                                closest = item
                            endif
                        endfor
                        return closest
        """.trimIndent()
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 0)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("closest_distance = 100.0", lines[0])
        assertEquals("closest = None", lines[1])
        assertEquals("for item in items :", lines[2])
        assertEquals("\tif something :", lines[3])
        assertEquals("\t\tclosest = item", lines[4])
        assertEquals("return closest", lines[5])
    }

    @Test
    fun `mixed tab and space original indentation is normalised`() {
        // Mix of tabs and spaces – both should be stripped
        val code = "\t\t\t   return 42"
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 0)
        assertEquals("return 42", result)
    }

    // ---- Comment lines ----

    @Test
    fun `comment lines are preserved at correct indentation`() {
        val code = """
            # top-level comment
            if x:
                # inner comment
                do_it()
            endif
        """.trimIndent()
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 0)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("# top-level comment", lines[0])
        assertEquals("if x:", lines[1])
        assertEquals("\t# inner comment", lines[2])
        assertEquals("\tdo_it()", lines[3])
    }

    // ---- baseDepth > 0 (wrapping inside a function def) ----

    @Test
    fun `baseDepth 1 adds one tab to all lines`() {
        val code = """
            for item in items:
                process(item)
            endfor
            return True
        """.trimIndent()
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 1)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("\tfor item in items:", lines[0])
        assertEquals("\t\tprocess(item)", lines[1])
        assertEquals("\treturn True", lines[2])
    }

    // ---- Cleaning-robot style macro (representative real-world snippet) ----

    @Test
    fun `get_closest_obstacle macro body rewritten correctly`() {
        // This is the body as it would arrive after $(path) resolution
        val code = """
            closest_distance = 100.0
            closest_obstacle = None
            for obstacle in [{"x": 1}] :
                distance = 5.0
                if distance < closest_distance :
                    closest_distance = distance
                    closest_obstacle = obstacle
                endif
            endfor
            return closest_obstacle
        """.trimIndent()

        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 1)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("\tclosest_distance = 100.0", lines[0])
        assertEquals("\tclosest_obstacle = None", lines[1])
        assertEquals("\tfor obstacle in [{\"x\": 1}] :", lines[2])
        assertEquals("\t\tdistance = 5.0", lines[3])
        assertEquals("\t\tif distance < closest_distance :", lines[4])
        assertEquals("\t\t\tclosest_distance = distance", lines[5])
        assertEquals("\t\t\tclosest_obstacle = obstacle", lines[6])
        assertEquals("\treturn closest_obstacle", lines[7])
        assertFalse(result.contains("endfor"))
        assertFalse(result.contains("endif"))
    }

    // ---- import statements ----

    @Test
    fun `import statements at top level are not indented at baseDepth 0`() {
        val code = """
            import math
            return math.sqrt(4.0)
        """.trimIndent()
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 0)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("import math", lines[0])
        assertEquals("return math.sqrt(4.0)", lines[1])
    }

    @Test
    fun `import inside function body at baseDepth 1`() {
        val code = """
            import math
            return math.pi
        """.trimIndent()
        val result = PythonIndentationRewriter.rewrite(code, baseDepth = 1)
        val lines = result.lines().filter { it.isNotEmpty() }
        assertEquals("\timport math", lines[0])
        assertEquals("\treturn math.pi", lines[1])
    }
}

