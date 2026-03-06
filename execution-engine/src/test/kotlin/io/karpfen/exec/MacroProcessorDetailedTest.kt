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

/**
 * Integration tests for MacroProcessor that actually execute Python code
 * via subprocesses and verify the full pipeline including:
 * - code transformation
 * - Python execution
 * - result parsing back to model types
 */
class MacroProcessorDetailedTest {

    private lateinit var mqp: ModelQueryProcessor
    private lateinit var macros: List<Macro>

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

        val distanceDoubledMacro = Macro(
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

        val circularDistanceMacro = Macro(
            name = "circular_bounding_box_distance",
            takes = listOf(
                TakesDirective("p1", "TwoDObject"),
                TakesDirective("p2", "TwoDObject")
            ),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock(
                """
                import math
                distance = @point_to_point_distance($(p1->position), $(p2->position))
                distance = distance - $(p1->diameter) / 2 - $(p2->diameter) / 2
                return distance
                """.trimIndent()
            ))
        )

        val getClosestObstacleMacro = Macro(
            name = "get_closest_obstacle",
            takes = listOf(
                TakesDirective("robot_position", "Vector"),
                TakesDirective("obstacles", """list("Obstacle")""")
            ),
            returns = ReturnsDirective("""reference("Obstacle")"""),
            definition = Definition(CodeBlock(
                """
                import json
                closest_distance = 100.0
                closest_obstacle = None
                for obstacle in $(obstacles) :
                    distance = @point_to_point_distance($(robot_position), obstacle["boundingBox"]["position"])
                    if distance < closest_distance :
                        closest_distance = distance
                        closest_obstacle = obstacle
                if closest_obstacle is not None :
                    return closest_obstacle
                return None
                """.trimIndent()
            ))
        )

        val returnStringMacro = Macro(
            name = "make_log_entry",
            takes = listOf(
                TakesDirective("pos", "Vector")
            ),
            returns = ReturnsDirective("string"),
            definition = Definition(CodeBlock(
                """
                return "Robot at x=" + str($(pos->x)) + " y=" + str($(pos->y))
                """.trimIndent()
            ))
        )

        val returnBoolMacro = Macro(
            name = "is_in_bounds",
            takes = listOf(
                TakesDirective("pos", "Vector")
            ),
            returns = ReturnsDirective("boolean"),
            definition = Definition(CodeBlock(
                """
                return $(pos->x) >= 0 and $(pos->x) <= 10 and $(pos->y) >= 0 and $(pos->y) <= 10
                """.trimIndent()
            ))
        )

        macros = listOf(
            pointDistanceMacro, addVectorsMacro, scaleMacro, distanceDoubledMacro,
            circularDistanceMacro, getClosestObstacleMacro, returnStringMacro,
            returnBoolMacro
        )
    }

    // ---- runPythonCode with real subprocess ----

    @Test
    fun `python subprocess returns integer`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.runPythonCode("print(2 + 3)")
        assertEquals("5", result)
    }

    @Test
    fun `python subprocess returns float`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.runPythonCode("print(3.14)")
        assertEquals("3.14", result)
    }

    @Test
    fun `python subprocess returns json dict`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val code = """
import json
print(json.dumps({"x": 1.5, "y": 2.5}))
        """.trimIndent()
        val result = processor.runPythonCode(code)?.toString()
        assertNotNull(result)
        assertTrue(result!!.contains("\"x\""))
        assertTrue(result.contains("1.5"))
    }

    @Test
    fun `python subprocess returns json boolean true`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val code = """
import json
print(json.dumps(True))
        """.trimIndent()
        val result = processor.runPythonCode(code)
        assertEquals("true", result)
    }

    @Test
    fun `python subprocess returns json boolean false`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val code = """
import json
print(json.dumps(False))
        """.trimIndent()
        val result = processor.runPythonCode(code)
        assertEquals("false", result)
    }

    @Test
    fun `python subprocess returns string`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.runPythonCode("print('hello world')")
        assertEquals("hello world", result)
    }

    @Test
    fun `python subprocess with math import`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val code = """
import math
print(math.sqrt(9))
        """.trimIndent()
        val result = processor.runPythonCode(code)
        assertEquals("3.0", result)
    }

    @Test
    fun `python subprocess returns null for no output`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.runPythonCode("x = 42")
        assertNull(result)
    }

    @Test
    fun `python subprocess throws on syntax error`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertThrows(RuntimeException::class.java) {
            processor.runPythonCode("def def def")
        }
    }

    @Test
    fun `python subprocess throws on runtime error`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertThrows(RuntimeException::class.java) {
            processor.runPythonCode("print(1/0)")
        }
    }

    @Test
    fun `python subprocess handles multiline output`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val code = """
import json
result = {"x": 1.0, "y": 2.0}
print(json.dumps(result))
        """.trimIndent()
        val result = processor.runPythonCode(code)?.toString()
        assertNotNull(result)
        assertTrue(result!!.startsWith("{"))
    }

    // ---- executeInlineMacro with real Python ----

    @Test
    fun `inline macro computes simple arithmetic with model values`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // position x=5.0, direction x=0.0 → 5.0 + 0.3*0.0 = 5.0
        val code = "return \$(boundingBox->position->x) + 0.3 * \$(direction->x)"
        val result = processor.executeInlineMacro(code, "number")
        assertNotNull(result)
        assertEquals(5.0, (result as Number).toDouble(), 0.001)
    }

    @Test
    fun `inline macro computes with y values`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // position y=5.0, direction y=1.0 → 5.0 + 0.3*1.0 = 5.3
        val code = "return \$(boundingBox->position->y) + 0.3 * \$(direction->y)"
        val result = processor.executeInlineMacro(code, "number")
        assertNotNull(result)
        assertEquals(5.3, (result as Number).toDouble(), 0.001)
    }

    @Test
    fun `inline macro returns boolean true`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val code = "return \$(d_closest_obstacle) > 1.0"
        val result = processor.executeInlineMacro(code, "boolean")
        assertEquals(true, result)
    }

    @Test
    fun `inline macro returns boolean false`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val code = "return \$(d_closest_obstacle) < 1.0"
        val result = processor.executeInlineMacro(code, "boolean")
        assertEquals(false, result)
    }

    @Test
    fun `inline macro with complex boolean expression`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // d_closest_obstacle=100, d_closest_wall=100 → 100 < 100 = false ∧ ... = false
        val code = "return \$(d_closest_obstacle) < \$(d_closest_wall) and \$(d_closest_obstacle) < 0.25"
        val result = processor.executeInlineMacro(code, "boolean")
        assertEquals(false, result)
    }

    @Test
    fun `inline macro returns string`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val code = "return 'position: ' + str(\$(boundingBox->position->x))"
        val result = processor.executeInlineMacro(code, "string")
        assertNotNull(result)
        assertTrue((result as String).contains("position:"))
        assertTrue(result.contains("5"))
    }

    @Test
    fun `inline macro calls another macro`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // Distance from turtle (5,5) to chair (2,3) = sqrt(9+4) = sqrt(13) ≈ 3.606
        val code = "return @point_to_point_distance(\$(boundingBox->position), \$(closest_obstacle->boundingBox->position))"
        val result = processor.executeInlineMacro(code, "number")
        assertNotNull(result)
        assertEquals(3.606, (result as Number).toDouble(), 0.01)
    }

    @Test
    fun `inline macro returns dict as DataObject`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val code = "return {\"x\": \$(boundingBox->position->x) + 1, \"y\": \$(boundingBox->position->y) + 1}"
        val result = processor.executeInlineMacro(code, "Vector")
        assertNotNull(result)
        assertTrue(result is DataObject)
        val obj = result as DataObject
        assertEquals("Vector", obj.ofType.name)
        assertEquals(6.0, obj.getProp("x").first())
        assertEquals(6.0, obj.getProp("y").first())
    }

    // ---- executeFullMacro with real Python ----

    @Test
    fun `full macro point_to_point_distance computes correctly`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // turtle position (5,5), chair position (2,3) → sqrt(9+4) ≈ 3.606
        val result = processor.executeFullMacro(
            "point_to_point_distance",
            listOf("boundingBox->position", "closest_obstacle->boundingBox->position")
        )
        assertNotNull(result)
        assertEquals(3.606, (result as Number).toDouble(), 0.01)
    }

    @Test
    fun `full macro add_vectors returns DataObject`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // position (5,5) + direction (0,1) = (5,6)
        val result = processor.executeFullMacro(
            "add_vectors",
            listOf("boundingBox->position", "direction")
        )
        assertNotNull(result)
        assertTrue(result is DataObject)
        val v = result as DataObject
        assertEquals("Vector", v.ofType.name)
        assertEquals(5.0, v.getProp("x").first())
        assertEquals(6.0, v.getProp("y").first())
    }

    @Test
    fun `full macro scale_number doubles value`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // d_closest_obstacle = 100.0 → 200.0
        val result = processor.executeFullMacro("scale_number", listOf("d_closest_obstacle"))
        assertNotNull(result)
        assertEquals(200.0, (result as Number).toDouble(), 0.001)
    }

    @Test
    fun `full macro distance_doubled uses dependency macro`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // distance (5,5)→(2,3) = sqrt(13) ≈ 3.606, doubled ≈ 7.211
        val result = processor.executeFullMacro(
            "distance_doubled",
            listOf("boundingBox->position", "closest_obstacle->boundingBox->position")
        )
        assertNotNull(result)
        assertEquals(7.211, (result as Number).toDouble(), 0.02)
    }

    @Test
    fun `full macro circular_bounding_box_distance accounts for diameters`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // turtle bb: diameter=0.2, position=(5,5); chair bb: diameter=1.0, position=(2,3)
        // point distance = sqrt(13) ≈ 3.606
        // circular distance = 3.606 - 0.2/2 - 1.0/2 = 3.606 - 0.1 - 0.5 = 3.006
        val result = processor.executeFullMacro(
            "circular_bounding_box_distance",
            listOf("boundingBox", "closest_obstacle->boundingBox")
        )
        assertNotNull(result)
        assertEquals(3.006, (result as Number).toDouble(), 0.02)
    }

    @Test
    fun `full macro get_closest_obstacle returns reference to closest`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // turtle position (5,5), chair (2,3) dist=sqrt(13)≈3.606, table (5,7) dist=2
        // table is closer
        val result = processor.executeFullMacro(
            "get_closest_obstacle",
            listOf("boundingBox->position", "obstacles")
        )
        assertNotNull(result)
        assertTrue(result is DataObject)
        val closest = result as DataObject
        // table at (5,7) should be closer to (5,5) than chair at (2,3)
        assertEquals("table", closest.id)
    }

    @Test
    fun `full macro make_log_entry returns string`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.executeFullMacro("make_log_entry", listOf("boundingBox->position"))
        assertNotNull(result)
        assertTrue(result is String)
        assertTrue((result as String).contains("Robot at"))
        assertTrue(result.contains("5"))
    }

    @Test
    fun `full macro is_in_bounds returns boolean true`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // position (5,5) is in bounds [0,10]
        val result = processor.executeFullMacro("is_in_bounds", listOf("boundingBox->position"))
        assertNotNull(result)
        assertEquals(true, result)
    }

    @Test
    fun `full macro throws on unknown macro name`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertThrows(IllegalArgumentException::class.java) {
            processor.executeFullMacro("nonexistent_macro")
        }
    }

    // ---- parseResult edge cases ----

    @Test
    fun `parseResult number from integer string`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("42", "number")
        assertEquals(42.0, result)
    }

    @Test
    fun `parseResult number from float string`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("3.14159", "number")
        assertEquals(3.14159, (result as Double), 0.00001)
    }

    @Test
    fun `parseResult number from negative`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("-7.5", "number")
        assertEquals(-7.5, result)
    }

    @Test
    fun `parseResult number throws on non-numeric`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertThrows(IllegalArgumentException::class.java) {
            processor.parseResult("not_a_number", "number")
        }
    }

    @Test
    fun `parseResult boolean from Python True`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertEquals(true, processor.parseResult("True", "boolean"))
    }

    @Test
    fun `parseResult boolean from Python False`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertEquals(false, processor.parseResult("False", "boolean"))
    }

    @Test
    fun `parseResult boolean from json true`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertEquals(true, processor.parseResult("true", "boolean"))
    }

    @Test
    fun `parseResult boolean from json false`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertEquals(false, processor.parseResult("false", "boolean"))
    }

    @Test
    fun `parseResult boolean throws on invalid`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertThrows(IllegalArgumentException::class.java) {
            processor.parseResult("maybe", "boolean")
        }
    }

    @Test
    fun `parseResult string returns raw value`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertEquals("hello world", processor.parseResult("hello world", "string"))
    }

    @Test
    fun `parseResult blank returns null`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertNull(processor.parseResult("", "number"))
        assertNull(processor.parseResult("   ", "boolean"))
        assertNull(processor.parseResult("  \n  ", "string"))
    }

    // ---- Complex type (DataObject) parsing ----

    @Test
    fun `parseResult Vector from JSON creates DataObject`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("""{"x": 1.5, "y": 2.5}""", "Vector")
        assertTrue(result is DataObject)
        val obj = result as DataObject
        assertEquals("Vector", obj.ofType.name)
        assertEquals(1.5, obj.getProp("x").first())
        assertEquals(2.5, obj.getProp("y").first())
    }

    @Test
    fun `parseResult Vector with integer values`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // Python may return integers in JSON: {"x": 5, "y": 6}
        val result = processor.parseResult("""{"x": 5, "y": 6}""", "Vector")
        assertTrue(result is DataObject)
        val obj = result as DataObject
        assertEquals(5.0, obj.getProp("x").first())
        assertEquals(6.0, obj.getProp("y").first())
    }

    @Test
    fun `parseResult Vector with negative values`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("""{"x": -3.5, "y": -1.2}""", "Vector")
        assertTrue(result is DataObject)
        val obj = result as DataObject
        assertEquals(-3.5, obj.getProp("x").first())
        assertEquals(-1.2, obj.getProp("y").first())
    }

    @Test
    fun `parseResult Vector with zero values`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("""{"x": 0, "y": 0}""", "Vector")
        assertTrue(result is DataObject)
        val obj = result as DataObject
        assertEquals(0.0, obj.getProp("x").first())
        assertEquals(0.0, obj.getProp("y").first())
    }

    @Test
    fun `parseResult TwoDObject with nested Vector`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val json = """{"diameter": 2.5, "position": {"x": 10.0, "y": 20.0}}"""
        val result = processor.parseResult(json, "TwoDObject")
        assertTrue(result is DataObject)
        val twoD = result as DataObject
        assertEquals("TwoDObject", twoD.ofType.name)
        assertEquals(2.5, twoD.getProp("diameter").first())
        val pos = twoD.getRel("position")
        assertEquals(1, pos.size)
        assertEquals(10.0, pos[0].getProp("x").first())
        assertEquals(20.0, pos[0].getProp("y").first())
    }

    @Test
    fun `parseResult existing Vector updates properties`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // turtlePosition exists with x=5.0, y=5.0
        val result = processor.parseResult(
            """{"__id__": "turtlePosition", "__type__": "Vector", "x": 99.0, "y": 88.0}""",
            "Vector"
        )
        assertTrue(result is DataObject)
        val obj = result as DataObject
        assertEquals("turtlePosition", obj.id)
        assertEquals(99.0, obj.getProp("x").first())
        assertEquals(88.0, obj.getProp("y").first())
    }

    @Test
    fun `parseResult new Vector without id`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("""{"x": 42.0, "y": 13.0}""", "Vector")
        assertTrue(result is DataObject)
        val obj = result as DataObject
        assertEquals("", obj.id)
        assertEquals(42.0, obj.getProp("x").first())
    }

    @Test
    fun `parseResult complex type throws on non-json`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertThrows(IllegalArgumentException::class.java) {
            processor.parseResult("not json", "Vector")
        }
    }

    @Test
    fun `parseResult unknown complex type throws`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertThrows(IllegalArgumentException::class.java) {
            processor.parseResult("""{"a": 1}""", "NonExistentType")
        }
    }

    // ---- Reference type parsing ----

    @Test
    fun `parseResult reference from JSON with __id__`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult(
            """{"__id__": "chair", "__type__": "Obstacle"}""",
            """reference("Obstacle")"""
        )
        assertTrue(result is DataObject)
        assertEquals("chair", (result as DataObject).id)
        assertEquals("Obstacle", result.ofType.name)
    }

    @Test
    fun `parseResult reference from plain id string`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("table", """reference("Obstacle")""")
        assertTrue(result is DataObject)
        assertEquals("table", (result as DataObject).id)
    }

    @Test
    fun `parseResult reference with quoted id`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("\"chair\"", """reference("Obstacle")""")
        assertTrue(result is DataObject)
        assertEquals("chair", (result as DataObject).id)
    }

    @Test
    fun `parseResult reference throws on unknown id`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        assertThrows(IllegalArgumentException::class.java) {
            processor.parseResult("nonexistent_id", """reference("Obstacle")""")
        }
    }

    // ---- List type parsing ----

    @Test
    fun `parseResult list of numbers`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("[1.0, 2.5, 3.7]", """list("number")""")
        assertTrue(result is List<*>)
        val list = result as List<*>
        assertEquals(3, list.size)
        assertEquals(1.0, list[0])
        assertEquals(2.5, list[1])
        assertEquals(3.7, list[2])
    }

    @Test
    fun `parseResult list of strings`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("""["hello", "world"]""", """list("string")""")
        assertTrue(result is List<*>)
        val list = result as List<*>
        assertEquals(2, list.size)
        assertEquals("hello", list[0])
        assertEquals("world", list[1])
    }

    @Test
    fun `parseResult list of booleans`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("[true, false, true]", """list("boolean")""")
        assertTrue(result is List<*>)
        val list = result as List<*>
        assertEquals(3, list.size)
        assertEquals(true, list[0])
        assertEquals(false, list[1])
        assertEquals(true, list[2])
    }

    @Test
    fun `parseResult list of complex objects`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val json = """[{"x": 1.0, "y": 2.0}, {"x": 3.0, "y": 4.0}]"""
        val result = processor.parseResult(json, """list("Vector")""")
        assertTrue(result is List<*>)
        val list = result as List<*>
        assertEquals(2, list.size)
        assertTrue(list[0] is DataObject)
        assertTrue(list[1] is DataObject)
        assertEquals(1.0, (list[0] as DataObject).getProp("x").first())
        assertEquals(4.0, (list[1] as DataObject).getProp("y").first())
    }

    @Test
    fun `parseResult empty list`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.parseResult("[]", """list("number")""")
        assertTrue(result is List<*>)
        assertEquals(0, (result as List<*>).size)
    }

    // ---- Full end-to-end pipeline: code → Python → parse ----

    @Test
    fun `end to end inline macro number computation via python`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val code = """
            import math
            return math.sqrt($(boundingBox->position->x) ** 2 + $(boundingBox->position->y) ** 2)
        """.trimIndent()
        val result = processor.executeInlineMacro(code, "number")
        assertNotNull(result)
        // sqrt(25 + 25) = sqrt(50) ≈ 7.071
        assertEquals(7.071, (result as Number).toDouble(), 0.01)
    }

    @Test
    fun `end to end inline macro dict return to DataObject via python`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val code = """return {"x": $(boundingBox->position->x) * 2, "y": $(boundingBox->position->y) * 3}"""
        val result = processor.executeInlineMacro(code, "Vector")
        assertTrue(result is DataObject)
        val v = result as DataObject
        assertEquals(10.0, v.getProp("x").first())
        assertEquals(15.0, v.getProp("y").first())
    }

    @Test
    fun `end to end full macro with chained dependency via python`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        // circular_bounding_box_distance depends on point_to_point_distance
        val result = processor.executeFullMacro(
            "circular_bounding_box_distance",
            listOf("boundingBox", "closest_obstacle->boundingBox")
        )
        assertNotNull(result)
        // sqrt((5-2)^2 + (5-3)^2) = sqrt(13) ≈ 3.606
        // 3.606 - 0.2/2 - 1.0/2 = 3.606 - 0.1 - 0.5 = 3.006
        assertEquals(3.006, (result as Number).toDouble(), 0.02)
    }

    @Test
    fun `end to end full macro get_closest_obstacle with list iteration via python`() {
        val processor = MacroProcessor(mqp.metamodel, mqp.model, macros, "turtle", mqp)
        val result = processor.executeFullMacro(
            "get_closest_obstacle",
            listOf("boundingBox->position", "obstacles")
        )
        assertNotNull(result)
        assertTrue(result is DataObject)
        // table at (5,7) is closer to (5,5) → distance=2
        // chair at (2,3) → distance=sqrt(13)≈3.606
        assertEquals("table", (result as DataObject).id)
    }
}
