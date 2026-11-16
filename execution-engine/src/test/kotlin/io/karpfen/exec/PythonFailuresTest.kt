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

import io.karpfen.io.karpfen.exec.ExecutionTier
import io.karpfen.io.karpfen.exec.ExpressionClassifier
import io.karpfen.io.karpfen.exec.MacroCodeTransformer
import io.karpfen.io.karpfen.exec.MacroProcessor
import io.karpfen.io.karpfen.exec.PythonExecutionException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What happens when code fails or misbehaves in the Python process that stays open, for inline code and for code that
 * calls a macro: code that raises, that does not compile, that returns nothing or the wrong kind of value, that writes
 * much to stderr or stdout, that leaves names behind, and a process that dies in a call. Every failure is a
 * [PythonExecutionException] or the error of the result, the next call works, no call blocks, and no call sees what an
 * earlier one left. A last test checks that code the runtime computes itself gives what Python gives.
 */
@Tag("python")
class PythonFailuresTest {

    private val processors = ArrayList<MacroProcessor>()
    private val before = PythonFixtures.pythonProcesses()

    @AfterEach
    fun close() {
        processors.forEach { it.pythonSessions.close() }
    }

    /** A processor with the [MACROS] that drops what code prints, since some tests print megabytes. */
    private fun processor(): MacroProcessor =
        PythonFixtures.processor(stateMachine = MACROS, output = { _, _ -> }).also { processors += it }

    /** The Python processes started since the test began that still run. */
    private fun newPythonProcesses(): Set<Long> = PythonFixtures.pythonProcesses() - before

    @Test
    fun `code that raises fails the call with the type and message of the exception with and without a macro`() {
        val processor = processor()

        val direct = assertFailsWith<PythonExecutionException> {
            processor.executeInlineMacro("raise ValueError(\"boom 'x' ü\")\nreturn 1", "number")
        }
        val open = assertFailsWith<PythonExecutionException> { processor.executeInlineMacro("return @fail()", "number") }

        assertEquals("Python error: ValueError: boom 'x' ü", direct.message)
        assertEquals("Python error: ValueError: boom 'x' ü", open.message)
        assertEquals(41.0, processor.executeInlineMacro("return @plus_one($(watts))", "number"))
        assertEquals(41.0, processor.executeInlineMacro("x = $(watts)\nreturn x + 1", "number"))
    }

    @Test
    fun `code with a syntax error fails the call with the syntax error with and without a macro`() {
        val processor = processor()

        val direct = assertFailsWith<PythonExecutionException> { processor.executeInlineMacro("return 1 +", "number") }
        val open = assertFailsWith<PythonExecutionException> { processor.executeInlineMacro("return @broken()", "number") }

        assertTrue(direct.message!!.startsWith("Python error: SyntaxError: "), direct.message)
        assertTrue(open.message!!.startsWith("Python error: SyntaxError: "), open.message)
        assertEquals(41.0, processor.executeInlineMacro("return @plus_one($(watts))", "number"))
    }

    @Test
    fun `code that returns nothing gives no result and code that returns the wrong kind is an error of the result`() {
        val processor = processor()

        assertNull(processor.executeInlineMacro("x = 1", "number"))
        assertNull(processor.executeInlineMacro("return None", "string"))
        assertNull(processor.executeInlineMacro("return @nothing()", "number"))
        val text = assertFailsWith<IllegalArgumentException> { processor.executeInlineMacro("return \"abc\"", "number") }
        val number = assertFailsWith<IllegalArgumentException> { processor.executeInlineMacro("return 2", "boolean") }
        val flag = assertFailsWith<IllegalArgumentException> { processor.executeInlineMacro("return 3 > 2", "number") }

        assertEquals("Expected number but got: abc", text.message)
        assertEquals("Expected boolean but got: 2", number.message)
        assertEquals("Expected number but got: true", flag.message)
    }

    @Test
    fun `code that writes much to stderr neither blocks nor changes its result with and without a macro`() {
        val processor = processor()
        val loud = "import sys\nsys.stderr.write(\"warning \" * 1000000)\nsys.stderr.flush()\nreturn 7"

        assertEquals(7.0, processor.executeInlineMacro(loud, "number"))
        assertEquals(7.0, processor.executeInlineMacro("return @loud()", "number"))
        val failing = assertFailsWith<PythonExecutionException> {
            processor.executeInlineMacro("import sys\nsys.stderr.write(\"noise\\n\" * 100000)\nraise KeyError(\"k\")", "number")
        }
        assertEquals("Python error: KeyError: 'k'", failing.message)
    }

    @Test
    fun `what code prints goes to the output and never into the result, also when it fails`() {
        val printed = ArrayList<Pair<String, String>>()
        val processor = PythonFixtures.processor(stateMachine = MACROS, output = { objectId, text -> printed += objectId to text })
            .also { processors += it }

        assertEquals("done", processor.executeInlineMacro("print(\"y\" * 3000000)\nreturn \"done\"", "string"))
        assertEquals("done", processor.executeInlineMacro("print(\"y\" * 3000000)\nreturn @echo(\"done\")", "string"))
        assertEquals(5.0, processor.executeInlineMacro("print(\"debug\")\nreturn 5", "number"))
        assertNull(processor.executeInlineMacro("print(\"only printed\")", "string"))
        assertFailsWith<PythonExecutionException> {
            processor.executeInlineMacro("print(\"before the error\")\nraise KeyError(\"k\")", "number")
        }

        assertEquals(
            listOf("y".repeat(3_000_000) + "\n", "y".repeat(3_000_000) + "\n", "debug\n", "only printed\n", "before the error\n"),
            printed.map { it.second }
        )
        assertEquals(setOf("a"), printed.map { it.first }.toSet())
    }

    @Test
    fun `a call does not see the names, functions and globals an earlier call left behind`() {
        val processor = processor()

        assertEquals(1.0, processor.executeInlineMacro("global leftover\nleftover = 5\nreturn 1", "number"))
        val name = assertFailsWith<PythonExecutionException> { processor.executeInlineMacro("return leftover", "number") }
        assertEquals("Python error: NameError: name 'leftover' is not defined", name.message)

        assertEquals(41.0, processor.executeInlineMacro("return @plus_one($(watts))", "number"))
        val macro = assertFailsWith<PythonExecutionException> { processor.executeInlineMacro("return plus_one(1)", "number") }
        assertEquals("Python error: NameError: name 'plus_one' is not defined", macro.message)

        assertEquals(1.0, processor.executeInlineMacro("import sys\nsys.stdout = None\nreturn 1", "number"))
        assertEquals(41.0, processor.executeInlineMacro("x = $(watts)\nreturn x + 1", "number"))
    }

    @Test
    fun `a module that one call imports stays loaded for the next call`() {
        val processor = processor()

        assertEquals(false, processor.executeInlineMacro("import sys\nreturn \"decimal\" in sys.modules", "boolean"))
        assertEquals(1.0, processor.executeInlineMacro("import decimal\nreturn 1", "number"))
        assertEquals(true, processor.executeInlineMacro("import sys\nreturn \"decimal\" in sys.modules", "boolean"))
    }

    @Test
    fun `an error message of a long result shows its beginning only`() {
        val processor = processor()

        val error = assertFailsWith<IllegalArgumentException> {
            processor.executeInlineMacro("return \"z\" * 100000", "number")
        }

        assertTrue(error.message!!.length < 300, "${error.message!!.length} characters")
        assertTrue(error.message!!.startsWith("Expected number but got: zzz"))
    }

    @Test
    fun `a process that dies in a call fails the call and the next call works with and without a macro`() {
        val processor = processor()

        assertEquals(41.0, processor.executeInlineMacro("x = $(watts)\nreturn x + 1", "number"))
        val dyingProcess = newPythonProcesses().single()
        val inline = assertFailsWith<PythonExecutionException> {
            processor.executeInlineMacro("__import__(\"os\")._exit(3)\nreturn 1", "number")
        }
        assertEquals("The Python process ended during the call", inline.message)
        PythonFixtures.await("the dead process is gone") { dyingProcess !in PythonFixtures.pythonProcesses() }
        assertEquals(41.0, processor.executeInlineMacro("x = $(watts)\nreturn x + 1", "number"))

        assertEquals(41.0, processor.executeInlineMacro("return @plus_one($(watts))", "number"))
        val firstProcess = newPythonProcesses().single()
        val open = assertFailsWith<PythonExecutionException> { processor.executeInlineMacro("return @die()", "number") }
        assertEquals("The Python process ended during the call", open.message)
        PythonFixtures.await("the dead process is gone") { firstProcess !in PythonFixtures.pythonProcesses() }
        assertEquals(41.0, processor.executeInlineMacro("return @plus_one($(watts))", "number"))
        assertEquals(1, newPythonProcesses().size)
    }

    @Test
    fun `SystemExit in the open process is an error of the call and keeps the process`() {
        val processor = processor()
        assertEquals(41.0, processor.executeInlineMacro("return @plus_one($(watts))", "number"))
        val process = newPythonProcesses().single()

        val exit = assertFailsWith<PythonExecutionException> { processor.executeInlineMacro("return @leave()", "number") }

        assertEquals("Python error: SystemExit: 2", exit.message)
        assertEquals(41.0, processor.executeInlineMacro("return @plus_one($(watts))", "number"))
        assertEquals(setOf(process), newPythonProcesses())
    }

    @Test
    fun `many calls in a row use one open process and leave no new process behind`() {
        val processor = processor()

        repeat(300) { assertEquals(41.0, processor.executeInlineMacro("return @plus_one($(watts))", "number")) }
        repeat(30) { assertEquals(41.0, processor.executeInlineMacro("x = $(watts)\nreturn x + 1", "number")) }

        assertEquals(1, newPythonProcesses().size)
        processor.pythonSessions.close()
        PythonFixtures.await("the open process ends with its processor") { newPythonProcesses().isEmpty() }
    }

    @Test
    fun `code the runtime computes itself gives what Python gives, results and errors alike`() {
        val processor = processor()
        val transformer = MacroCodeTransformer(processor.modelQueryProcessor, emptyList())
        val expressions = listOf(
            "1 / 0", "5 % 0", "5 // 0", "0 ** -1", "(-8) ** 0.5", "2 ** 0.5", "-2 ** 2", "2 ** -1", "2 ** 3 ** 2",
            "7 % -3", "-7 % 3", "-7.5 // 2", "1 // 0.1", "5 // 2", "-5 // 2", "0.1 + 0.2", "1e308 * 10", "10 ** 400",
            "2 or False", "0 or 5", "1 and 0", "0 and 1 / 0", "not 0", "True + True", "1 == True", "3 != 3.0",
            "1 < 2 < 3", "3 > 2 > 1 == 1", "1 < 2 and 2 < 1 or 3 >= 3", "-(3)", "+-+1", "1 +", "2 + 2", "1.5 * 2"
        )
        for (expression in expressions) {
            for (target in listOf("number", "boolean", "string")) {
                val code = transformer.transformInlineCode("return $expression", processor.context)
                assertEquals(ExecutionTier.SIMPLE, ExpressionClassifier.classify(code), expression)
                val computed = runCatching { processor.executeInlineMacro("return $expression", target) }
                val python = runCatching { processor.runPython(code)?.let { processor.parseResult(it, target) } }

                assertEquals(describe(python), describe(computed), "$expression as $target")
            }
        }
    }

    /** A result or a failure as text, so results and failures compare alike. */
    private fun describe(outcome: Result<Any?>): String =
        outcome.fold({ "result $it" }, { "${it::class.simpleName}: ${it.message}" })

    private companion object {
        /** Macros whose code runs in the open process when inline code calls them with `@name`. */
        val MACROS = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES { INITIAL STATE "off" { } }
                TRANSITIONS { }
                MACROS {
                    MACRO "plus_one" {
                        TAKES("value", "number")
                        RETURNS("number")
                        DEFINITION { EVAL { return $(value) + 1 } }
                    }
                    MACRO "echo" {
                        TAKES("value", "string")
                        RETURNS("string")
                        DEFINITION { EVAL { return $(value) } }
                    }
                    MACRO "fail" {
                        RETURNS("number")
                        DEFINITION { EVAL { raise ValueError("boom 'x' ü") } }
                    }
                    MACRO "broken" {
                        RETURNS("number")
                        DEFINITION { EVAL { return 1 + } }
                    }
                    MACRO "nothing" {
                        RETURNS("number")
                        DEFINITION { EVAL { return None } }
                    }
                    MACRO "loud" {
                        RETURNS("number")
                        DEFINITION {
                            EVAL {
                                import sys
                                sys.stderr.write("warning " * 1000000)
                                return 7
                            }
                        }
                    }
                    MACRO "die" {
                        RETURNS("number")
                        DEFINITION {
                            EVAL {
                                import os
                                os._exit(3)
                            }
                        }
                    }
                    MACRO "leave" {
                        RETURNS("number")
                        DEFINITION {
                            EVAL {
                                import sys
                                sys.exit(2)
                            }
                        }
                    }
                }
            }
        """
    }
}
