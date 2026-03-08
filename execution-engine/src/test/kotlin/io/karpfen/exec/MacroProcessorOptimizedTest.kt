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

import instance.DataObject
import io.karpfen.io.karpfen.exec.MacroProcessor
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import io.karpfen.io.karpfen.exec.MacroCodeTransformer
import io.karpfen.io.karpfen.exec.ExpressionClassifier
import io.karpfen.io.karpfen.exec.ExecutionTier
import states.Macro
import states.macros.CodeBlock
import states.macros.Definition
import states.macros.ReturnsDirective
import states.macros.TakesDirective
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests verifying that the 3-tier optimized macro execution produces
 * correct results equivalent to the original Python-only execution path.
 *
 * Tests cover:
 * - SIMPLE tier: inline EVAL expressions from the robot demo (arithmetic, boolean conditions)
 * - MEDIUM tier: self-contained code without imports
 * - COMPLEX tier: code with imports (math, numpy) and function definitions
 * - Cross-tier equivalence: same code produces identical results regardless of execution tier
 */
class MacroProcessorOptimizedTest {

    private lateinit var mqp: ModelQueryProcessor
    private lateinit var macros: List<Macro>
    private lateinit var processor: MacroProcessor

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

        val scaleMacro = Macro(
            name = "scale_number",
            takes = listOf(
                TakesDirective("value", "number")
            ),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock(
                """
                return $(value) * 2
                """.trimIndent()
            ))
        )

        val distanceWithDependencyMacro = Macro(
            name = "distance_doubled",
            takes = listOf(
                TakesDirective("p1", "Vector"),
                TakesDirective("p2", "Vector")
            ),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock(
                """
                d = @point_to_point_distance($(p1), $(p2))
                return d * 2
                """.trimIndent()
            ))
        )

        macros = listOf(pointDistanceMacro, addVectorsMacro, scaleMacro, distanceWithDependencyMacro)

        processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
    }

    @AfterEach
    fun tearDown() {
        processor.close()
    }

    // ---- SIMPLE tier: inline EVAL expressions (robot demo patterns) ----

    @Test
    fun `SIMPLE tier - robot drive x position update with no movement`() {
        // return $(boundingBox->position->x) + 0.3 * $(direction->x)
        // turtle: x=5.0, direction.x=0.0 → 5.0 + 0.3 * 0.0 = 5.0
        val code = "return \$(boundingBox->position->x) + 0.3 * \$(direction->x)"
        val result = processor.executeInlineMacro(code, "number")
        assertNotNull(result)
        assertEquals(5.0, (result as Number).toDouble(), 0.001)

        // Verify this was classified as SIMPLE
        val codeTransformer = MacroCodeTransformer(mqp, macros)
        val transformedCode = codeTransformer.transformInlineCode(code, processor.context)
        assertEquals(ExecutionTier.SIMPLE, ExpressionClassifier.classify(transformedCode))
    }

    @Test
    fun `SIMPLE tier - robot drive y position update with movement`() {
        // return $(boundingBox->position->y) + 0.3 * $(direction->y)
        // turtle: y=5.0, direction.y=1.0 → 5.0 + 0.3 * 1.0 = 5.3
        val code = "return \$(boundingBox->position->y) + 0.3 * \$(direction->y)"
        val result = processor.executeInlineMacro(code, "number")
        assertNotNull(result)
        assertEquals(5.3, (result as Number).toDouble(), 0.001)
    }

    @Test
    fun `SIMPLE tier - robot drive slow update`() {
        // return $(boundingBox->position->y) + 0.1 * $(direction->y)
        // 5.0 + 0.1 * 1.0 = 5.1
        val code = "return \$(boundingBox->position->y) + 0.1 * \$(direction->y)"
        val result = processor.executeInlineMacro(code, "number")
        assertNotNull(result)
        assertEquals(5.1, (result as Number).toDouble(), 0.001)
    }

    @Test
    fun `SIMPLE tier - transition condition obstacle closer returns false`() {
        // return $(d_closest_obstacle) < $(d_closest_wall) and $(d_closest_obstacle) < 0.25
        // d_closest_obstacle=100.0, d_closest_wall=100.0 → 100 < 100 and 100 < 0.25 = false
        val code = "return \$(d_closest_obstacle) < \$(d_closest_wall) and \$(d_closest_obstacle) < 0.25"
        val result = processor.executeInlineMacro(code, "boolean")
        assertNotNull(result)
        assertEquals(false, result)
    }

    @Test
    fun `SIMPLE tier - transition condition wall closer returns false`() {
        val code = "return \$(d_closest_wall) < \$(d_closest_obstacle) and \$(d_closest_wall) < 0.25"
        val result = processor.executeInlineMacro(code, "boolean")
        assertNotNull(result)
        assertEquals(false, result)
    }

    @Test
    fun `SIMPLE tier - transition drive slow condition`() {
        // return $(d_closest_obstacle) < 1.0 or $(d_closest_wall) < 1.0
        // 100.0 < 1.0 or 100.0 < 1.0 = false
        val code = "return \$(d_closest_obstacle) < 1.0 or \$(d_closest_wall) < 1.0"
        val result = processor.executeInlineMacro(code, "boolean")
        assertNotNull(result)
        assertEquals(false, result)
    }

    @Test
    fun `SIMPLE tier - transition drive fast condition`() {
        // return $(d_closest_obstacle) >= 1.0 and $(d_closest_wall) >= 1.0
        // 100.0 >= 1.0 and 100.0 >= 1.0 = true
        val code = "return \$(d_closest_obstacle) >= 1.0 and \$(d_closest_wall) >= 1.0"
        val result = processor.executeInlineMacro(code, "boolean")
        assertNotNull(result)
        assertEquals(true, result)
    }

    @Test
    fun `SIMPLE tier - boolean true from comparison`() {
        val code = "return \$(d_closest_obstacle) > 1.0"
        val result = processor.executeInlineMacro(code, "boolean")
        assertNotNull(result)
        assertEquals(true, result)
    }

    @Test
    fun `SIMPLE tier - boolean false from comparison`() {
        val code = "return \$(d_closest_obstacle) < 1.0"
        val result = processor.executeInlineMacro(code, "boolean")
        assertNotNull(result)
        assertEquals(false, result)
    }

    // ---- COMPLEX tier: full macros with imports ----

    @Test
    fun `COMPLEX tier - point distance via full macro`() {
        // Uses import math → COMPLEX tier
        // Distance between turtlePosition(5,5) and chairPosition(2,3) = sqrt(9+4) = sqrt(13) ≈ 3.606
        val result = processor.executeFullMacro(
            "point_to_point_distance",
            listOf("boundingBox->position", "closest_obstacle->boundingBox->position")
        )
        assertNotNull(result)
        val distance = (result as Number).toDouble()
        assertEquals(3.606, distance, 0.01)
    }

    @Test
    fun `COMPLEX tier - distance doubled macro with dependency`() {
        // distance_doubled calls @point_to_point_distance which uses import math
        // = sqrt(13) * 2 ≈ 7.211
        val result = processor.executeFullMacro(
            "distance_doubled",
            listOf("boundingBox->position", "closest_obstacle->boundingBox->position")
        )
        assertNotNull(result)
        val distance = (result as Number).toDouble()
        assertEquals(7.211, distance, 0.01)
    }

    @Test
    fun `COMPLEX tier - add vectors returns DataObject`() {
        // add_vectors with position(5,5) and direction(0,1) → {x: 5.0, y: 6.0}
        val result = processor.executeFullMacro(
            "add_vectors",
            listOf("boundingBox->position", "direction")
        )
        assertNotNull(result)
        assertTrue(result is DataObject)
    }

    // ---- SIMPLE tier via executeFullMacro ----

    @Test
    fun `SIMPLE tier - scale number full macro`() {
        // scale_number: return $(value) * 2
        // With d_closest_obstacle = 100.0 → 100.0 * 2 = 200.0
        // After transformation: return 100.0 * 2
        // This should be classified as SIMPLE
        val result = processor.executeFullMacro(
            "scale_number",
            listOf("d_closest_obstacle")
        )
        assertNotNull(result)
        assertEquals(200.0, (result as Number).toDouble(), 0.001)
    }

    // ---- runPythonCodeDirect (MEDIUM tier) ----

    @Test
    fun `runPythonCodeDirect executes simple code`() {
        val result = processor.runPythonCodeDirect("print(42)")
        assertEquals("42", result)
    }

    @Test
    fun `runPythonCodeDirect executes multi-line code`() {
        val code = """
            x = 5 + 3
            print(x * 2)
        """.trimIndent()
        val result = processor.runPythonCodeDirect(code)
        assertEquals("16", result)
    }

    @Test
    fun `runPythonCodeDirect returns null for no output`() {
        val result = processor.runPythonCodeDirect("x = 42")
        assertNull(result)
    }

    // ---- runPythonCodeViaSession (COMPLEX tier) ----

    @Test
    fun `runPythonCodeViaSession executes code`() {
        val result = processor.runPythonCodeViaSession("print(42)")
        assertEquals("42", result)
    }

    @Test
    fun `runPythonCodeViaSession handles imports`() {
        val code = """
            import math
            print(math.sqrt(16.0))
        """.trimIndent()
        val result = processor.runPythonCodeViaSession(code)
        assertEquals("4.0", result)
    }

    // ---- Equivalence tests: same expression, same result across tiers ----

    @Test
    fun `equivalence - simple arithmetic gives same result as Python`() {
        // Use runPythonCode (original path) for reference, then executeInlineMacro (optimized)
        val pythonResult = processor.runPythonCode(
            "print(5.0 + 0.3 * 1.0)"
        )
        assertEquals("5.3", pythonResult)

        val code = "return \$(boundingBox->position->y) + 0.3 * \$(direction->y)"
        val optimizedResult = processor.executeInlineMacro(code, "number")
        assertEquals(5.3, (optimizedResult as Number).toDouble(), 0.001)
    }

    @Test
    fun `equivalence - boolean condition gives same result as Python`() {
        val pythonResult = processor.runPythonCode(
            "print(100.0 >= 1.0 and 100.0 >= 1.0)"
        )
        assertEquals("True", pythonResult)

        val code = "return \$(d_closest_obstacle) >= 1.0 and \$(d_closest_wall) >= 1.0"
        val optimizedResult = processor.executeInlineMacro(code, "boolean")
        assertEquals(true, optimizedResult)
    }

    // ---- Original runPythonCode still works ----

    @Test
    fun `runPythonCode legacy path still works`() {
        val result = processor.runPythonCode("print(3 + 4 * 2)")
        assertEquals("11", result)
    }

    @Test
    fun `runPythonCode legacy path handles json`() {
        val code = """
            import json
            result = {"x": 1.0, "y": 2.0}
            print(json.dumps(result))
        """.trimIndent()
        val result = processor.runPythonCode(code)
        assertNotNull(result)
        assertTrue(result.toString().contains("\"x\""))
        assertTrue(result.toString().contains("\"y\""))
    }
}
