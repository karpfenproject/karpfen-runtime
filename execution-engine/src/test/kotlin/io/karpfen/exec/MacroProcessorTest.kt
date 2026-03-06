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
import states.Macro
import states.macros.CodeBlock
import states.macros.Definition
import states.macros.ReturnsDirective
import states.macros.TakesDirective
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MacroProcessorTest {

    private lateinit var mqp: ModelQueryProcessor
    private lateinit var macros: List<Macro>

    @BeforeEach
    fun setUp() {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        mqp = ModelQueryProcessor(metamodel, model)

        // Define test macros
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

        // Macro that depends on point_to_point_distance
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
    }

    // ---- runPythonCode ----

    @Test
    fun `runPythonCode executes simple python and returns stdout`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        val result = processor.runPythonCode("print(42)")
        assertEquals("42", result)
    }

    @Test
    fun `runPythonCode executes arithmetic`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        val result = processor.runPythonCode("print(3 + 4 * 2)")
        assertEquals("11", result)
    }

    @Test
    fun `runPythonCode returns null for no output`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        val result = processor.runPythonCode("x = 42")
        assertNull(result)
    }

    @Test
    fun `runPythonCode throws on invalid python`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        assertThrows(RuntimeException::class.java) {
            processor.runPythonCode("this is not valid python!!!")
        }
    }

    @Test
    fun `runPythonCode handles json output`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
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

    // ---- executeInlineMacro ----

    @Test
    fun `executeInlineMacro returns number result`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        // turtle's boundingBox->position->x = 5.0, direction->x = 0.0
        val code = "return \$(boundingBox->position->x) + 0.3 * \$(direction->x)"
        val result = processor.executeInlineMacro(code, "number")
        assertNotNull(result)
        assertEquals(5.0, (result as Number).toDouble(), 0.001)
    }

    @Test
    fun `executeInlineMacro returns number with y values`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        // turtle's boundingBox->position->y = 5.0, direction->y = 1.0
        val code = "return \$(boundingBox->position->y) + 0.3 * \$(direction->y)"
        val result = processor.executeInlineMacro(code, "number")
        assertNotNull(result)
        assertEquals(5.3, (result as Number).toDouble(), 0.001)
    }

    @Test
    fun `executeInlineMacro returns boolean true`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        // d_closest_obstacle = 100.0
        val code = "return \$(d_closest_obstacle) > 1.0"
        val result = processor.executeInlineMacro(code, "boolean")
        assertNotNull(result)
        assertEquals(true, result)
    }

    @Test
    fun `executeInlineMacro returns boolean false`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        val code = "return \$(d_closest_obstacle) < 1.0"
        val result = processor.executeInlineMacro(code, "boolean")
        assertNotNull(result)
        assertEquals(false, result)
    }

    @Test
    fun `executeInlineMacro with complex expression`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        // d_closest_obstacle = 100.0, d_closest_wall = 100.0
        val code = "return \$(d_closest_obstacle) < \$(d_closest_wall) and \$(d_closest_obstacle) < 0.25"
        val result = processor.executeInlineMacro(code, "boolean")
        assertNotNull(result)
        assertEquals(false, result)
    }

    // ---- executeFullMacro ----

    @Test
    fun `executeFullMacro computes point distance`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        // Position: (5,5), Direction: (0,1) — distance = sqrt(25+16) = sqrt(41)? No...
        // Let's use two known positions: turtlePosition (5,5) and chairPosition (2,3)
        // distance = sqrt((5-2)^2 + (5-3)^2) = sqrt(9+4) = sqrt(13) ≈ 3.606
        val result = processor.executeFullMacro(
            "point_to_point_distance",
            listOf("boundingBox->position", "closest_obstacle->boundingBox->position")
        )
        assertNotNull(result)
        val distance = (result as Number).toDouble()
        assertEquals(3.606, distance, 0.01)
    }

    @Test
    fun `executeFullMacro returns vector as DataObject`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        // add_vectors with position (5,5) and direction (0,1)
        // Result should be {"x": 5.0, "y": 6.0}
        val result = processor.executeFullMacro(
            "add_vectors",
            listOf("boundingBox->position", "direction")
        )
        assertNotNull(result)
        assertTrue(result is DataObject)
        val dataObj = result as DataObject
        assertEquals("Vector", dataObj.ofType.name)
        assertEquals(5.0, dataObj.getProp("x").first())
        assertEquals(6.0, dataObj.getProp("y").first())
    }

    @Test
    fun `executeFullMacro scales number`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        // d_closest_obstacle = 100.0, scale_number returns 200.0
        val result = processor.executeFullMacro(
            "scale_number",
            listOf("d_closest_obstacle")
        )
        assertNotNull(result)
        assertEquals(200.0, (result as Number).toDouble(), 0.001)
    }

    @Test
    fun `executeFullMacro with dependency on another macro`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        // distance_doubled calls point_to_point_distance internally
        // turtlePosition (5,5) and chairPosition (2,3), distance = sqrt(13) ≈ 3.606, doubled ≈ 7.211
        val result = processor.executeFullMacro(
            "distance_doubled",
            listOf("boundingBox->position", "closest_obstacle->boundingBox->position")
        )
        assertNotNull(result)
        val doubled = (result as Number).toDouble()
        assertEquals(7.211, doubled, 0.02)
    }

    @Test
    fun `executeFullMacro throws on unknown macro`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        assertThrows(IllegalArgumentException::class.java) {
            processor.executeFullMacro("nonexistent_macro")
        }
    }

    // ---- parseResult ----

    @Test
    fun `parseResult number`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        val result = processor.parseResult("3.14", "number")
        assertEquals(3.14, result)
    }

    @Test
    fun `parseResult boolean true`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        assertEquals(true, processor.parseResult("true", "boolean"))
        assertEquals(true, processor.parseResult("True", "boolean"))
    }

    @Test
    fun `parseResult boolean false`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        assertEquals(false, processor.parseResult("false", "boolean"))
        assertEquals(false, processor.parseResult("False", "boolean"))
    }

    @Test
    fun `parseResult string`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        assertEquals("hello", processor.parseResult("hello", "string"))
    }

    @Test
    fun `parseResult complex type Vector returns DataObject`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        val result = processor.parseResult("""{"x": 1.5, "y": 2.5}""", "Vector")
        assertTrue(result is DataObject)
        val dataObj = result as DataObject
        assertEquals("Vector", dataObj.ofType.name)
        assertEquals(1.5, dataObj.getProp("x").first())
        assertEquals(2.5, dataObj.getProp("y").first())
    }

    @Test
    fun `parseResult reference type`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        val result = processor.parseResult(
            """{"__id__": "chair", "__type__": "Obstacle"}""",
            """reference("Obstacle")"""
        )
        assertTrue(result is DataObject)
        assertEquals("chair", (result as DataObject).id)
    }

    @Test
    fun `parseResult reference type plain id`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        val result = processor.parseResult("chair", """reference("Obstacle")""")
        assertTrue(result is DataObject)
        assertEquals("chair", (result as DataObject).id)
    }

    @Test
    fun `parseResult blank returns null`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        assertNull(processor.parseResult("", "number"))
        assertNull(processor.parseResult("   ", "number"))
    }

    // ---- DataObject construction / update from JSON ----

    @Test
    fun `parseResult complex type with existing __id__ updates existing object`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        // turtlePosition exists with x=5.0, y=5.0 — update it to x=7.0, y=8.0
        val result = processor.parseResult(
            """{"__id__": "turtlePosition", "__type__": "Vector", "x": 7.0, "y": 8.0}""",
            "Vector"
        )
        assertTrue(result is DataObject)
        val dataObj = result as DataObject
        assertEquals("turtlePosition", dataObj.id)
        assertEquals(7.0, dataObj.getProp("x").first())
        assertEquals(8.0, dataObj.getProp("y").first())
    }

    @Test
    fun `parseResult complex type without __id__ creates new DataObject`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        val result = processor.parseResult(
            """{"x": 99.0, "y": 42.0}""",
            "Vector"
        )
        assertTrue(result is DataObject)
        val dataObj = result as DataObject
        assertEquals("Vector", dataObj.ofType.name)
        assertEquals("", dataObj.id) // no id given
        assertEquals(99.0, dataObj.getProp("x").first())
        assertEquals(42.0, dataObj.getProp("y").first())
    }

    @Test
    fun `parseResult reference with JSON dict containing __id__ returns existing DataObject`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        val result = processor.parseResult(
            """{"__id__": "table", "__type__": "Obstacle"}""",
            """reference("Obstacle")"""
        )
        assertTrue(result is DataObject)
        assertEquals("table", (result as DataObject).id)
        assertEquals("Obstacle", result.ofType.name)
    }

    @Test
    fun `parseResult nested complex type with embedded object`() {
        val processor = MacroProcessor(
            mqp.metamodel, mqp.model, macros, "turtle", mqp
        )
        // TwoDObject has diameter (number) and position (Vector, embedded)
        val json = """{"diameter": 2.5, "position": {"x": 10.0, "y": 20.0}}"""
        val result = processor.parseResult(json, "TwoDObject")
        assertTrue(result is DataObject)
        val twoD = result as DataObject
        assertEquals("TwoDObject", twoD.ofType.name)
        assertEquals(2.5, twoD.getProp("diameter").first())
        val position = twoD.getRel("position")
        assertEquals(1, position.size)
        assertEquals(10.0, position[0].getProp("x").first())
        assertEquals(20.0, position[0].getProp("y").first())
    }
}
