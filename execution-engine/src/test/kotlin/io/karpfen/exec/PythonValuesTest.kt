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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Hands values to Python and back in both ways code runs in Python: in a new process (plain code) and in the process
 * that stays open (code that calls a macro with `@name`, or code longer than 8000 characters). Every text of
 * [PythonFixtures.HARD_TEXTS] must arrive in Python as it is in the model and come back unchanged: as a property, inside
 * an object handed over as `$(object)`, as a macro argument, and as the value code returns. Large values and long code
 * cross the limit of 8000 characters.
 */
@Tag("python")
class PythonValuesTest {

    private val processors = ArrayList<MacroProcessor>()

    @AfterEach
    fun close() {
        processors.forEach { it.close() }
    }

    private fun processor(stateMachine: String? = MACROS): MacroProcessor =
        PythonFixtures.processor(stateMachine = stateMachine).also { processors += it }

    @Test
    fun `a hard text read as a property arrives and returns unchanged in both ways`() {
        val processor = processor()
        for (text in PythonFixtures.HARD_TEXTS) {
            processor.modelQueryProcessor.setScalar("a", "label", text)

            assertEquals(text, processor.executeInlineMacro("return $(label)", "string"), "new process: $text")
            assertEquals(text, processor.executeInlineMacro("return @echo($(label))", "string"), "open process: $text")
            assertEquals(
                text.codePointCount(0, text.length).toDouble(),
                processor.executeInlineMacro("return len($(label))", "number"),
                "length in Python: $text"
            )
        }
    }

    @Test
    fun `a hard text inside an object handed over as a dictionary arrives unchanged`() {
        val processor = processor()
        for (text in PythonFixtures.HARD_TEXTS) {
            processor.modelQueryProcessor.setScalar("bulbA", "label", text)

            assertEquals(text, processor.executeInlineMacro("return $(bulb)[\"label\"]", "string"), text)
            assertEquals(text, processor.executeInlineMacro("return @label_of($(bulb))", "string"), text)
        }
    }

    @Test
    fun `a hard text as the argument of a full macro arrives unchanged`() {
        val processor = processor()
        for (text in PythonFixtures.HARD_TEXTS) {
            processor.modelQueryProcessor.setScalar("a", "label", text)

            assertEquals(text, processor.executeFullMacro("echo", listOf("label")), text)
            processor.modelQueryProcessor.setScalar("bulbA", "label", text)
            assertEquals(text, processor.executeFullMacro("label_of", listOf("bulb")), text)
        }
    }

    @Test
    fun `a hard text written as a literal into the code comes back unchanged`() {
        val processor = processor()
        assertEquals("a\"b'c\\d\ne\tf ü", processor.executeInlineMacro("return \"a\\\"b'c\\\\d\\ne\\tf ü\"", "string"))
        assertEquals("", processor.executeInlineMacro("return \"\"", "string"))
    }

    @Test
    fun `special numbers arrive in Python and come back`() {
        val processor = processor()
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.5, 1e300, 12345678.0)) {
            processor.modelQueryProcessor.setScalar("a", "watts", value)

            assertEquals(value, processor.executeInlineMacro("return $(watts)", "number") as Double, "$value")
            assertEquals(value, processor.executeInlineMacro("return @echo($(watts))", "number") as Double, "$value")
        }
    }

    @Test
    fun `a long list and its sum come back across the limit of 8000 characters`() {
        val processor = processor()
        for (size in listOf(10, 1_500, 1_700, 20_000)) {
            val readings = (1..size).map { it.toDouble() }
            processor.modelQueryProcessor.setSimpleList("a", "readings", readings)

            assertEquals(readings.sum(), processor.executeInlineMacro("return sum($(readings))", "number"), "size $size")
            assertEquals(readings, processor.executeInlineMacro("return $(readings)", """list("number")"""), "size $size")
        }
    }

    @Test
    fun `the same code runs in a new process below 8000 characters and in the open process above`() {
        val processor = processor()
        val transformer = MacroCodeTransformer(processor.modelQueryProcessor, emptyList())
        val tiers = HashSet<ExecutionTier>()
        for (size in 1_000..2_000 step 100) {
            processor.modelQueryProcessor.setSimpleList("a", "readings", (1..size).map { it.toDouble() })
            val code = transformer.transformInlineCode("return len($(readings))", processor.context)
            tiers += ExpressionClassifier.classify(code)

            assertEquals(size.toDouble(), processor.executeInlineMacro("return len($(readings))", "number"))
        }
        assertEquals(setOf(ExecutionTier.MEDIUM, ExecutionTier.COMPLEX), tiers)
    }

    @Test
    fun `a large object and a long code text are handed over in both ways`() {
        val processor = processor()
        val log = (1..5_000).map { "entry $it with \"quotes\" and ü" }
        processor.modelQueryProcessor.setSimpleList("a", "log", log)
        val longCode = (1..400).joinToString("\n") { "x$it = $it" } + "\nreturn len($(log)) + x400"

        assertEquals(5_400.0, processor.executeInlineMacro(longCode, "number"))
        assertEquals(log.last(), processor.executeInlineMacro("return $(log)[-1]", "string"))
        assertEquals(log.last(), processor.executeInlineMacro("return @last($(log))", "string"))
    }

    @Test
    fun `a dictionary that code returns with hard texts changes the object in place`() {
        val processor = processor()
        val result = processor.executeInlineMacro(
            "return {\"__id__\": \"bulbA\", \"label\": \"a\\\"b'c\\\\d\\ne ü\"}",
            "Bulb"
        )

        assertEquals("bulbA", (result as io.karpfen.dsl.instance.DataObject).id)
        assertEquals("a\"b'c\\d\ne ü", processor.modelQueryProcessor.getDataObjectById("bulbA").getProp("label").single())
    }

    @Test
    fun `objects that know each other are refused as an error of the code`() {
        val processor = processor()
        val mqp = processor.modelQueryProcessor
        mqp.appendObjectRelation("a", "partners", mqp.getDataObjectById("b"))
        mqp.appendObjectRelation("b", "partners", mqp.getDataObjectById("a"))

        val refusal = assertFailsWith<IllegalStateException> { processor.executeInlineMacro("return $(partners)", "number") }

        assertEquals("Reference loop through object 'b' cannot be handed to code", refusal.message)
        assertEquals("plain", processor.executeInlineMacro("return $(partners->label)", "string"))
    }

    private companion object {
        /** Macros that hand their argument back; calling them with `@name` runs the code in the open process. */
        val MACROS = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES { INITIAL STATE "off" { } }
                TRANSITIONS { }
                MACROS {
                    MACRO "echo" {
                        TAKES("value", "string")
                        RETURNS("string")
                        DEFINITION { EVAL { return $(value) } }
                    }
                    MACRO "label_of" {
                        TAKES("bulb", "Bulb")
                        RETURNS("string")
                        DEFINITION { EVAL { return $(bulb->label) } }
                    }
                    MACRO "last" {
                        TAKES("entries", "string")
                        RETURNS("string")
                        DEFINITION { EVAL { return $(entries)[-1] } }
                    }
                }
            }
        """
    }
}
