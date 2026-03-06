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
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import states.Macro
import states.macros.CodeBlock
import states.macros.Definition
import states.macros.ReturnsDirective
import states.macros.TakesDirective
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MacroCodeTransformerTest {

    private lateinit var mqp: ModelQueryProcessor
    private lateinit var transformer: MacroCodeTransformer

    @BeforeEach
    fun setUp() {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        mqp = ModelQueryProcessor(metamodel, model)

        // Create a simple test macro for dependency resolution
        val pointDistanceMacro = Macro(
            name = "point_to_point_distance",
            takes = listOf(
                TakesDirective("p1", "Vector"),
                TakesDirective("p2", "Vector")
            ),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock(
                """
                import math
                return math.sqrt(($(p1->x) - $(p2->x)) ** 2 + ($(p1->y) - $(p2->y)) ** 2)
                """.trimIndent()
            ))
        )

        val addVectorMacro = Macro(
            name = "add_vectors",
            takes = listOf(
                TakesDirective("v1", "Vector"),
                TakesDirective("v2", "Vector")
            ),
            returns = ReturnsDirective("Vector"),
            definition = Definition(CodeBlock(
                """
                return {"x": $(v1->x) + $(v2->x), "y": $(v1->y) + $(v2->y)}
                """.trimIndent()
            ))
        )

        transformer = MacroCodeTransformer(mqp, listOf(pointDistanceMacro, addVectorMacro))
    }

    // ---- transformInlineCode ----

    @Test
    fun `transformInlineCode replaces simple model references`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return \$(boundingBox->position->x) + 0.3 * \$(direction->x)"
        val result = transformer.transformInlineCode(code, turtle)

        // Should contain the resolved values
        assertTrue(result.contains("5"))  // x = 5.0
        assertTrue(result.contains("0"))  // direction x = 0.0
        assertTrue(result.contains("0.3"))
        // Should not contain $( anymore
        assertFalse(result.contains("\$("))
    }

    @Test
    fun `transformInlineCode wraps code in karpfen_main function`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return 42"
        val result = transformer.transformInlineCode(code, turtle)

        assertTrue(result.contains("def __karpfen_main__():"))
        assertTrue(result.contains("__result__ = __karpfen_main__()"))
    }

    @Test
    fun `transformInlineCode includes json import`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return 42"
        val result = transformer.transformInlineCode(code, turtle)

        assertTrue(result.contains("import json"))
    }

    @Test
    fun `transformInlineCode handles boolean return value printing`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return \$(d_closest_obstacle) < 1.0"
        val result = transformer.transformInlineCode(code, turtle)

        assertTrue(result.contains("isinstance(__result__, bool)"))
        assertTrue(result.contains("json.dumps(__result__)"))
    }

    @Test
    fun `transformInlineCode resolves DataObject to python dict`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return \$(boundingBox->position)"
        val result = transformer.transformInlineCode(code, turtle)

        // The position should be resolved to a dict-like structure
        assertTrue(result.contains("\"x\":"))
        assertTrue(result.contains("\"y\":"))
    }

    // ---- transformFullMacroCode ----

    @Test
    fun `transformFullMacroCode replaces parameter references`() {
        val macro = Macro(
            name = "test_macro",
            takes = listOf(TakesDirective("p", "Vector")),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock("return \$(p->x) + \$(p->y)"))
        )

        val resolvedArgs = mapOf("p" to """{"x": 3.0, "y": 4.0, "__id__": "test", "__type__": "Vector"}""")
        val result = transformer.transformFullMacroCode(macro, resolvedArgs)

        // Should contain dict access pattern
        assertTrue(result.contains("__param_p__"))
        assertTrue(result.contains("[\"x\"]"))
        assertTrue(result.contains("[\"y\"]"))
        // Should assign the param variable
        assertTrue(result.contains("__param_p__ ="))
    }

    @Test
    fun `transformFullMacroCode handles direct parameter reference`() {
        val macro = Macro(
            name = "test_macro",
            takes = listOf(TakesDirective("val1", "number")),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock("return \$(val1) * 2"))
        )

        val resolvedArgs = mapOf("val1" to "5.0")
        val result = transformer.transformFullMacroCode(macro, resolvedArgs)

        assertTrue(result.contains("5.0"))
        assertTrue(result.contains("* 2"))
    }

    // ---- macro call resolution ----

    @Test
    fun `transformFullMacroCode resolves at-sign macro calls`() {
        val macro = Macro(
            name = "caller_macro",
            takes = listOf(
                TakesDirective("p1", "Vector"),
                TakesDirective("p2", "Vector")
            ),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock("return @point_to_point_distance(\$(p1), \$(p2))"))
        )

        val resolvedArgs = mapOf(
            "p1" to """{"x": 1.0, "y": 2.0, "__id__": "a", "__type__": "Vector"}""",
            "p2" to """{"x": 4.0, "y": 6.0, "__id__": "b", "__type__": "Vector"}"""
        )
        val result = transformer.transformFullMacroCode(macro, resolvedArgs)

        // Should contain a function definition for point_to_point_distance
        assertTrue(result.contains("def point_to_point_distance("))
        // Should contain the call
        assertTrue(result.contains("point_to_point_distance("))
    }

    @Test
    fun `transformInlineCode resolves at-sign macro calls with context data`() {
        // Create a transformer with the macros
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return @point_to_point_distance(\$(boundingBox->position), \$(direction))"
        val result = transformer.transformInlineCode(code, turtle)

        // Should contain the function definition
        assertTrue(result.contains("def point_to_point_distance("))
        // Should contain the actual call
        assertTrue(result.contains("point_to_point_distance("))
        // Should not contain @ anymore
        assertFalse(result.contains("@point_to_point_distance"))
    }
}

