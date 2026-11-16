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
 * What happens when code fails or misbehaves, in a new process and in the process that stays open: code that raises,
 * that does not compile, that returns nothing or the wrong kind of value, that writes much to stderr or stdout, and a
 * process that dies in a call. Every failure is a [PythonExecutionException] or the error of the result, the next call
 * works, and no call blocks. A last test checks that code the runtime computes itself gives what Python gives.
 */
@Tag("python")
class PythonFailuresTest {

    private val processors = ArrayList<MacroProcessor>()
    private val before = PythonFixtures.pythonProcesses()

    @AfterEach
    fun close() {
        processors.forEach { it.close() }
    }

    private fun processor(): MacroProcessor = PythonFixtures.processor(stateMachine = MACROS).also { processors += it }

    /** The Python processes started since the test began that still run. */
    private fun newPythonProcesses(): Set<Long> = PythonFixtures.pythonProcesses() - before

    @Test
    fun `code that raises fails the call with the type and message of the exception in both ways`() {
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
    fun `code with a syntax error fails the call with the syntax error in both ways`() {
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
    fun `code that writes much to stderr neither blocks nor changes its result in both ways`() {
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
    fun `what code prints to stdout comes before its result and does not block in both ways`() {
        val processor = processor()
        val chatty = "print(\"y\" * 3000000)\nreturn \"done\""

        val direct = processor.executeInlineMacro(chatty, "string") as String
        val open = processor.executeInlineMacro("print(\"y\" * 3000000)\nreturn @echo(\"done\")", "string") as String

        assertEquals("y".repeat(3_000_000) + "\ndone", direct)
        assertEquals(direct, open)
        val asNumber = assertFailsWith<IllegalArgumentException> {
            processor.executeInlineMacro("print(\"debug\")\nreturn 5", "number")
        }
        assertEquals("Expected number but got: debug\n5", asNumber.message)
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
    fun `a process that dies in a call fails the call and the next call works in both ways`() {
        val processor = processor()

        val direct = assertFailsWith<PythonExecutionException> {
            processor.executeInlineMacro("__import__(\"os\")._exit(3)\nreturn 1", "number")
        }
        assertEquals("Python ended with exit code 3", direct.message)
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
        processor.close()
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
            "1 < 2 < 3", "3 > 2 > 1 == 1", "1 < 2 and 2 < 1 or 3 >= 3", "-(3)", "+-+1", "1 +"
        )
        for (expression in expressions) {
            for (target in listOf("number", "boolean")) {
                val code = transformer.transformInlineCode("return $expression", processor.context)
                assertEquals(ExecutionTier.SIMPLE, ExpressionClassifier.classify(code), expression)
                val computed = runCatching { processor.executeInlineMacro("return $expression", target) }
                val python = runCatching { processor.runPythonCodeDirect(code)?.let { processor.parseResult(it, target) } }

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
