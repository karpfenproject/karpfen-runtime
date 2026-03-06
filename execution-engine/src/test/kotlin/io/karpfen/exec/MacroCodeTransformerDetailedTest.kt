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

/**
 * Detailed unit tests for MacroCodeTransformer focusing on individual
 * code transformation cases, edge cases, and the cleaning robot example patterns.
 */
class MacroCodeTransformerDetailedTest {

    private lateinit var mqp: ModelQueryProcessor
    private lateinit var allMacros: List<Macro>
    private lateinit var transformer: MacroCodeTransformer

    @BeforeEach
    fun setUp() {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        mqp = ModelQueryProcessor(metamodel, model)

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

        val addVectorsMacro = Macro(
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

        val oppositeDirectionMacro = Macro(
            name = "opposite_direction_with_variance",
            takes = listOf(
                TakesDirective("point_robot", "Vector"),
                TakesDirective("point_obstacle", "Vector")
            ),
            returns = ReturnsDirective("Vector"),
            definition = Definition(CodeBlock(
                """
                import random
                import math
                obstacle_x = $(point_obstacle->x) + random.uniform(-0.1, 0.1)
                obstacle_y = $(point_obstacle->y) + random.uniform(-0.1, 0.1)
                x_facing_away_from_obstacle = $(point_robot->x) - obstacle_x
                y_facing_away_from_obstacle = $(point_robot->y) - obstacle_y
                length = math.sqrt(x_facing_away_from_obstacle ** 2 + y_facing_away_from_obstacle ** 2)
                x_new = x_facing_away_from_obstacle / length
                y_new = y_facing_away_from_obstacle / length
                return {"x": x_new, "y": y_new}
                """.trimIndent()
            ))
        )

        // Macro that uses local variables with $() syntax (like get_closest_obstacle)
        val getClosestObstacleMacro = Macro(
            name = "get_closest_obstacle",
            takes = listOf(
                TakesDirective("robot_position", "Vector"),
                TakesDirective("obstacles", """list("Obstacle")""")
            ),
            returns = ReturnsDirective("""reference("Obstacle")"""),
            definition = Definition(CodeBlock(
                """
                closest_distance = 100.0
                closest_obstacle = None
                for obstacle in $(obstacles) :
                    distance = @point_to_point_distance($(robot_position), $(obstacle->boundingBox->position))
                    if distance < closest_distance :
                        closest_distance = distance
                        closest_obstacle = $(obstacle)
                return $(closest_obstacle)
                """.trimIndent()
            ))
        )

        // Macro that calls another macro and passes a dict literal as arg
        val wallDirectionMacro = Macro(
            name = "opposite_direction_from_wall_with_variance",
            takes = listOf(
                TakesDirective("point_robot", "Vector"),
                TakesDirective("wall_point", "Vector"),
                TakesDirective("wall_dir", "Vector")
            ),
            returns = ReturnsDirective("Vector"),
            definition = Definition(CodeBlock(
                """
                import numpy as np
                import random
                wall_dir_arr = np.array([$(wall_dir->x), $(wall_dir->y)])
                wall_dir_arr = wall_dir_arr / np.linalg.norm(wall_dir_arr)
                wall_pt = np.array([$(wall_point->x), $(wall_point->y)])
                robot_pt = np.array([$(point_robot->x), $(point_robot->y)])
                foot = wall_pt + np.dot(robot_pt - wall_pt, wall_dir_arr) * wall_dir_arr
                return @opposite_direction_with_variance($(point_robot), {"x": foot[0], "y": foot[1]})
                """.trimIndent()
            ))
        )

        allMacros = listOf(
            pointDistanceMacro, addVectorsMacro, oppositeDirectionMacro,
            getClosestObstacleMacro, wallDirectionMacro
        )
        transformer = MacroCodeTransformer(mqp, allMacros)
    }

    // ---- Inline code: model reference resolution ----

    @Test
    fun `inline code replaces simple number property`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return \$(d_closest_obstacle)"
        val result = transformer.transformInlineCode(code, turtle)
        assertTrue(result.contains("100"))
        assertFalse(result.contains("\$("))
    }

    @Test
    fun `inline code replaces deeply nested path`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return \$(boundingBox->position->x)"
        val result = transformer.transformInlineCode(code, turtle)
        assertTrue(result.contains("5"))
        assertFalse(result.contains("\$("))
    }

    @Test
    fun `inline code replaces multiple references in one line`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return \$(boundingBox->position->x) + \$(boundingBox->position->y)"
        val result = transformer.transformInlineCode(code, turtle)
        // Both should be 5
        assertFalse(result.contains("\$("))
        // The code line should contain "5" twice (or a combined expression)
        val mainLine = result.lines().first { it.contains("return") && it.contains("+") }
        assertNotNull(mainLine)
    }

    @Test
    fun `inline code resolves DataObject to python dict`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return \$(boundingBox->position)"
        val result = transformer.transformInlineCode(code, turtle)
        assertTrue(result.contains("\"__id__\": \"turtlePosition\""))
        assertTrue(result.contains("\"x\":"))
        assertTrue(result.contains("\"y\":"))
    }

    @Test
    fun `inline code resolves boolean comparison`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return \$(d_closest_obstacle) < 1.0"
        val result = transformer.transformInlineCode(code, turtle)
        assertTrue(result.contains("100"))
        assertTrue(result.contains("< 1.0"))
    }

    @Test
    fun `inline code with combined boolean expressions`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return \$(d_closest_obstacle) < \$(d_closest_wall) and \$(d_closest_obstacle) < 0.25"
        val result = transformer.transformInlineCode(code, turtle)
        assertFalse(result.contains("\$("))
        assertTrue(result.contains("and"))
    }

    // ---- Full macro code: parameter resolution ----

    @Test
    fun `full macro replaces direct parameter reference for number`() {
        val macro = Macro(
            name = "double_it",
            takes = listOf(TakesDirective("val1", "number")),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock("return \$(val1) * 2"))
        )
        val resolvedArgs = mapOf("val1" to "42")
        val result = transformer.transformFullMacroCode(macro, resolvedArgs)
        assertTrue(result.contains("42"))
        assertTrue(result.contains("* 2"))
        assertFalse(result.contains("\$("))
    }

    @Test
    fun `full macro replaces parameter dict access`() {
        val macro = Macro(
            name = "get_x",
            takes = listOf(TakesDirective("p", "Vector")),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock("return \$(p->x)"))
        )
        val resolvedArgs = mapOf("p" to """{"x": 3.0, "y": 4.0, "__id__": "test", "__type__": "Vector"}""")
        val result = transformer.transformFullMacroCode(macro, resolvedArgs)
        assertTrue(result.contains("__param_p__"))
        assertTrue(result.contains("[\"x\"]"))
        assertTrue(result.contains("__param_p__ ="))
    }

    @Test
    fun `full macro replaces multiple parameters`() {
        val macro = Macro(
            name = "sum_xy",
            takes = listOf(
                TakesDirective("p1", "Vector"),
                TakesDirective("p2", "Vector")
            ),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock("return \$(p1->x) + \$(p2->y)"))
        )
        val resolvedArgs = mapOf(
            "p1" to """{"x": 1.0, "y": 2.0, "__id__": "a", "__type__": "Vector"}""",
            "p2" to """{"x": 3.0, "y": 4.0, "__id__": "b", "__type__": "Vector"}"""
        )
        val result = transformer.transformFullMacroCode(macro, resolvedArgs)
        assertTrue(result.contains("__param_p1__"))
        assertTrue(result.contains("__param_p2__"))
        assertTrue(result.contains("[\"x\"]"))
        assertTrue(result.contains("[\"y\"]"))
    }

    // ---- Local variable resolution (edge case from get_closest_obstacle) ----

    @Test
    fun `full macro treats local variable $(localVar) as python variable`() {
        val macro = Macro(
            name = "test_local",
            takes = listOf(TakesDirective("items", """list("Vector")""")),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock(
                """
                best = None
                for item in $(items) :
                    best = $(item)
                return $(best)
                """.trimIndent()
            ))
        )
        val resolvedArgs = mapOf("items" to """[{"x": 1, "y": 2}]""")
        val result = transformer.transformFullMacroCode(macro, resolvedArgs)

        // $(item) and $(best) should become Python variable names, not $item
        assertFalse(result.contains("\$(item)"))
        assertFalse(result.contains("\$(best)"))
        assertFalse(result.contains("\$item"))
        assertFalse(result.contains("\$best"))
        // But $(items) should be resolved from parameters
        assertFalse(result.contains("\$(items)"))
    }

    @Test
    fun `full macro treats local variable $(localVar-path) as dict access`() {
        val macro = Macro(
            name = "test_local_path",
            takes = listOf(TakesDirective("items", """list("Obstacle")""")),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock(
                """
                for item in $(items) :
                    x = $(item->boundingBox->position->x)
                return x
                """.trimIndent()
            ))
        )
        val resolvedArgs = mapOf("items" to """[{"boundingBox": {"position": {"x": 5}}}]""")
        val result = transformer.transformFullMacroCode(macro, resolvedArgs)

        // $(item->boundingBox->position->x) should become item["boundingBox"]["position"]["x"]
        assertTrue(result.contains("""item["boundingBox"]["position"]["x"]"""))
        assertFalse(result.contains("\$(item"))
    }

    // ---- Macro dependency (@macroName) resolution ----

    @Test
    fun `inline code generates function definition for macro dependency`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return @point_to_point_distance(\$(boundingBox->position), \$(direction))"
        val result = transformer.transformInlineCode(code, turtle)

        assertTrue(result.contains("def point_to_point_distance("))
        // @ sign should be removed
        assertFalse(result.contains("@point_to_point_distance"))
        // The call should still be present
        assertTrue(result.contains("point_to_point_distance("))
    }

    @Test
    fun `full macro generates function definition for dependency`() {
        val macro = Macro(
            name = "caller",
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

        assertTrue(result.contains("def point_to_point_distance("))
        assertFalse(result.contains("@point_to_point_distance"))
    }

    @Test
    fun `macro with chained dependency generates both function definitions`() {
        // opposite_direction_from_wall_with_variance depends on opposite_direction_with_variance
        val macro = allMacros.first { it.name == "opposite_direction_from_wall_with_variance" }
        val resolvedArgs = mapOf(
            "point_robot" to """{"x": 5.0, "y": 5.0, "__id__": "r", "__type__": "Vector"}""",
            "wall_point" to """{"x": 0.0, "y": 10.0, "__id__": "wp", "__type__": "Vector"}""",
            "wall_dir" to """{"x": 1.0, "y": 0.0, "__id__": "wd", "__type__": "Vector"}"""
        )
        val result = transformer.transformFullMacroCode(macro, resolvedArgs)

        assertTrue(result.contains("def opposite_direction_with_variance("))
        assertFalse(result.contains("@opposite_direction_with_variance"))
    }

    @Test
    fun `macro call with dict literal argument is preserved`() {
        // The wall direction macro passes {"x": foot[0], "y": foot[1]} as argument
        val macro = allMacros.first { it.name == "opposite_direction_from_wall_with_variance" }
        val resolvedArgs = mapOf(
            "point_robot" to """{"x": 5.0, "y": 5.0, "__id__": "r", "__type__": "Vector"}""",
            "wall_point" to """{"x": 0.0, "y": 10.0, "__id__": "wp", "__type__": "Vector"}""",
            "wall_dir" to """{"x": 1.0, "y": 0.0, "__id__": "wd", "__type__": "Vector"}"""
        )
        val result = transformer.transformFullMacroCode(macro, resolvedArgs)

        // Dict literal should be preserved in the call
        assertTrue(result.contains("{\"x\": foot[0], \"y\": foot[1]}"))
    }

    @Test
    fun `macro dependency does not generate duplicate function definitions`() {
        val turtle = mqp.getDataObjectById("turtle")
        // Two calls to the same macro
        val code = """
            a = @point_to_point_distance($(boundingBox->position), $(direction))
            b = @point_to_point_distance($(direction), $(boundingBox->position))
            return a + b
        """.trimIndent()
        val result = transformer.transformInlineCode(code, turtle)

        // Count occurrences of the function definition
        val defCount = Regex("def point_to_point_distance\\(").findAll(result).count()
        assertEquals(1, defCount, "Function definition should appear exactly once")
    }

    // ---- Python code structure ----

    @Test
    fun `generated code includes json import`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = transformer.transformInlineCode("return 42", turtle)
        assertTrue(result.contains("import json"))
    }

    @Test
    fun `generated code wraps in karpfen_main function`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = transformer.transformInlineCode("return 42", turtle)
        assertTrue(result.contains("def __karpfen_main__():"))
        assertTrue(result.contains("__result__ = __karpfen_main__()"))
    }

    @Test
    fun `generated code prints dict result as json`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = transformer.transformInlineCode("return {\"x\": 1}", turtle)
        assertTrue(result.contains("isinstance(__result__, dict)"))
        assertTrue(result.contains("json.dumps(__result__)"))
    }

    @Test
    fun `generated code prints list result as json`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = transformer.transformInlineCode("return [1, 2, 3]", turtle)
        assertTrue(result.contains("isinstance(__result__, list)"))
    }

    @Test
    fun `generated code prints bool result as json dumps`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = transformer.transformInlineCode("return True", turtle)
        assertTrue(result.contains("isinstance(__result__, bool)"))
        assertTrue(result.contains("json.dumps(__result__)"))
    }

    @Test
    fun `generated code indents inline code inside function body`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = transformer.transformInlineCode("x = 1\nreturn x", turtle)
        // Each line of user code should be indented (4 spaces) inside __karpfen_main__
        val lines = result.lines()
        val mainIdx = lines.indexOfFirst { it.contains("def __karpfen_main__():") }
        assertTrue(mainIdx >= 0)
        // Next lines should be indented
        assertTrue(lines[mainIdx + 1].startsWith("    "))
    }

    // ---- get_closest_obstacle pattern tests ----

    @Test
    fun `get_closest_obstacle macro body resolves correctly`() {
        val macro = allMacros.first { it.name == "get_closest_obstacle" }
        val resolvedArgs = mapOf(
            "robot_position" to """{"x": 5, "y": 5, "__id__": "rp", "__type__": "Vector"}""",
            "obstacles" to """[{"__id__": "chair", "__type__": "Obstacle", "boundingBox": {"__id__": "cb", "__type__": "TwoDObject", "diameter": 1.0, "position": {"__id__": "cp", "__type__": "Vector", "x": 2, "y": 3}}}]"""
        )
        val result = transformer.transformFullMacroCode(macro, resolvedArgs)

        // $(obstacles) should be resolved to the list literal
        assertFalse(result.contains("\$(obstacles)"))

        // $(obstacle) and $(obstacle->...) should be local Python variables
        assertFalse(result.contains("\$(obstacle)"))
        assertFalse(result.contains("\$(closest_obstacle)"))

        // $(obstacle->boundingBox->position) should become obstacle["boundingBox"]["position"]
        assertTrue(result.contains("""obstacle["boundingBox"]["position"]"""))

        // point_to_point_distance function should be defined
        assertTrue(result.contains("def point_to_point_distance("))
    }

    // ---- Multiline code handling ----

    @Test
    fun `inline code handles multiline code blocks`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = """
            x = $(boundingBox->position->x)
            y = $(boundingBox->position->y)
            return x + y
        """.trimIndent()
        val result = transformer.transformInlineCode(code, turtle)
        assertFalse(result.contains("\$("))
        assertTrue(result.contains("return x + y"))
    }

    @Test
    fun `inline code handles import statements`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = """
            import math
            return math.sqrt($(boundingBox->position->x) ** 2 + $(boundingBox->position->y) ** 2)
        """.trimIndent()
        val result = transformer.transformInlineCode(code, turtle)
        assertTrue(result.contains("import math"))
        assertFalse(result.contains("\$("))
    }

    // ---- Edge cases in arg splitting ----

    @Test
    fun `splitMacroCallArgs handles dict literal argument`() {
        val turtle = mqp.getDataObjectById("turtle")
        // A macro call where one arg is a dict literal
        val code = """return @add_vectors($(boundingBox->position), {"x": 1, "y": 2})"""
        val result = transformer.transformInlineCode(code, turtle)
        // Dict literal should be preserved
        assertTrue(result.contains(""""x": 1"""))
        assertTrue(result.contains(""""y": 2"""))
    }

    @Test
    fun `splitMacroCallArgs handles no arguments`() {
        val noArgMacro = Macro(
            name = "constant_macro",
            takes = emptyList(),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock("return 42"))
        )
        val transformerWithNoArgMacro = MacroCodeTransformer(mqp, allMacros + noArgMacro)
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return @constant_macro()"
        val result = transformerWithNoArgMacro.transformInlineCode(code, turtle)
        assertTrue(result.contains("def constant_macro("))
        assertTrue(result.contains("constant_macro()"))
    }

    // ---- Macro body function generation ----

    @Test
    fun `generated macro function has correct parameter names`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return @add_vectors(\$(boundingBox->position), \$(direction))"
        val result = transformer.transformInlineCode(code, turtle)
        assertTrue(result.contains("def add_vectors(v1, v2):"))
    }

    @Test
    fun `generated macro function body resolves params to variable access`() {
        val turtle = mqp.getDataObjectById("turtle")
        val code = "return @add_vectors(\$(boundingBox->position), \$(direction))"
        val result = transformer.transformInlineCode(code, turtle)
        // Inside the function body, $(v1->x) should become v1["x"]
        assertTrue(result.contains("""v1["x"]"""))
        assertTrue(result.contains("""v2["y"]"""))
    }
}

