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
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Detailed tests for ModelQueryProcessor focusing on edge cases in:
 * - object lookup
 * - path resolution
 * - Python dict serialization
 * - literal conversion
 */
class ModelQueryProcessorDetailedTest {

    private lateinit var mqp: ModelQueryProcessor

    @BeforeEach
    fun setUp() {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        mqp = ModelQueryProcessor(metamodel, model)
    }

    // ---- getDataObjectById edge cases ----

    @Test
    fun `getDataObjectById finds room (root) object`() {
        val room = mqp.getDataObjectById("APB 2101")
        assertEquals("Room", room.ofType.name)
    }

    @Test
    fun `getDataObjectById finds deeply nested Vector`() {
        val pos = mqp.getDataObjectById("chairPosition")
        assertEquals("Vector", pos.ofType.name)
        assertEquals(2.0, pos.getProp("x").first())
        assertEquals(3.0, pos.getProp("y").first())
    }

    @Test
    fun `getDataObjectById finds all wall objects`() {
        val wallIds = listOf("wall_top", "wall_right", "wall_bottom", "wall_left")
        for (id in wallIds) {
            val wall = mqp.getDataObjectById(id)
            assertEquals("Wall", wall.ofType.name)
            assertEquals(id, wall.id)
        }
    }

    @Test
    fun `getDataObjectById finds wall sub-objects`() {
        val p = mqp.getDataObjectById("wallTopP")
        assertEquals("Vector", p.ofType.name)
        assertEquals(0.0, p.getProp("x").first())
        assertEquals(10.0, p.getProp("y").first())
    }

    @Test
    fun `getDataObjectById throws for empty id`() {
        assertThrows(IllegalArgumentException::class.java) {
            mqp.getDataObjectById("")
        }
    }

    @Test
    fun `getDataObjectById throws for whitespace id`() {
        assertThrows(IllegalArgumentException::class.java) {
            mqp.getDataObjectById("   ")
        }
    }

    // ---- getValueOfThing complex paths ----

    @Test
    fun `getValueOfThing traverses through linked object to property`() {
        // turtle -> closest_obstacle (link to chair) -> boundingBox -> position -> x = 2.0
        val result = mqp.getValueOfThing("turtle", listOf("closest_obstacle", "boundingBox", "position", "x"))
        assertEquals(2.0, result)
    }

    @Test
    fun `getValueOfThing traverses through linked wall`() {
        // turtle -> closest_wall (link to wall_top) -> p -> y = 10.0
        val result = mqp.getValueOfThing("turtle", listOf("closest_wall", "p", "y"))
        assertEquals(10.0, result)
    }

    @Test
    fun `getValueOfThing returns list of walls`() {
        val result = mqp.getValueOfThing("turtle", listOf("walls"))
        assertTrue(result is List<*>)
        assertEquals(4, (result as List<*>).size)
    }

    @Test
    fun `getValueOfThing returns list of obstacles`() {
        val result = mqp.getValueOfThing("turtle", listOf("obstacles"))
        assertTrue(result is List<*>)
        assertEquals(2, (result as List<*>).size)
    }

    @Test
    fun `getValueOfThing from room to robot`() {
        val result = mqp.getValueOfThing("APB 2101", listOf("robot"))
        assertTrue(result is DataObject)
        assertEquals("turtle", (result as DataObject).id)
    }

    @Test
    fun `getValueOfThing throws for uninitialized list property (log)`() {
        // log is defined in the metamodel as list("string") but not initialized in the model.
        // Therefore, getValueOfThing correctly throws because the property object does not exist.
        assertThrows(IllegalArgumentException::class.java) {
            mqp.getValueOfThing("turtle", listOf("log"))
        }
    }

    @Test
    fun `getValueOfThing returns diameter property`() {
        val result = mqp.getValueOfThing("turtleBoundingBox", listOf("diameter"))
        assertEquals(0.2, result)
    }

    // ---- resolvePathFromObject edge cases ----

    @Test
    fun `resolvePathFromObject from room to robot diameter`() {
        val room = mqp.getDataObjectById("APB 2101")
        val result = mqp.resolvePathFromObject(room, "robot->boundingBox->diameter")
        assertEquals(0.2, result)
    }

    @Test
    fun `resolvePathFromObject resolves linked list`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = mqp.resolvePathFromObject(turtle, "obstacles")
        assertTrue(result is List<*>)
        assertEquals(2, (result as List<*>).size)
    }

    @Test
    fun `resolvePathFromObject resolves single linked object`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = mqp.resolvePathFromObject(turtle, "closest_obstacle")
        assertTrue(result is DataObject)
        assertEquals("chair", (result as DataObject).id)
    }

    @Test
    fun `resolvePathFromObject deep path through link`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = mqp.resolvePathFromObject(turtle, "closest_obstacle->boundingBox->position->y")
        assertEquals(3.0, result)
    }

    @Test
    fun `resolvePathFromObject throws on invalid segment`() {
        val turtle = mqp.getDataObjectById("turtle")
        assertThrows(IllegalArgumentException::class.java) {
            mqp.resolvePathFromObject(turtle, "nonExistent")
        }
    }

    @Test
    fun `resolvePathFromObject throws on navigating through simple property`() {
        val turtle = mqp.getDataObjectById("turtle")
        assertThrows(IllegalArgumentException::class.java) {
            mqp.resolvePathFromObject(turtle, "d_closest_obstacle->something")
        }
    }

    // ---- dataObjectToPythonDict edge cases ----

    @Test
    fun `dataObjectToPythonDict includes __id__ and __type__`() {
        val pos = mqp.getDataObjectById("turtlePosition")
        val dict = mqp.dataObjectToPythonDict(pos)
        assertTrue(dict.contains("\"__id__\": \"turtlePosition\""))
        assertTrue(dict.contains("\"__type__\": \"Vector\""))
    }

    @Test
    fun `dataObjectToPythonDict formats numbers correctly`() {
        val pos = mqp.getDataObjectById("turtlePosition")
        val dict = mqp.dataObjectToPythonDict(pos)
        // 5.0 should be formatted as "5" (integer-like double)
        assertTrue(dict.contains("\"x\": 5"))
        assertTrue(dict.contains("\"y\": 5"))
    }

    @Test
    fun `dataObjectToPythonDict formats fractional numbers correctly`() {
        val bb = mqp.getDataObjectById("turtleBoundingBox")
        val dict = mqp.dataObjectToPythonDict(bb)
        assertTrue(dict.contains("\"diameter\": 0.2"))
    }

    @Test
    fun `dataObjectToPythonDict serializes nested embedded objects`() {
        val bb = mqp.getDataObjectById("turtleBoundingBox")
        val dict = mqp.dataObjectToPythonDict(bb)
        // Should contain the position sub-dict
        assertTrue(dict.contains("\"position\":"))
        assertTrue(dict.contains("\"__id__\": \"turtlePosition\""))
    }

    @Test
    fun `dataObjectToPythonDict serializes Robot with all properties`() {
        val turtle = mqp.getDataObjectById("turtle")
        val dict = mqp.dataObjectToPythonDict(turtle)
        assertTrue(dict.contains("\"d_closest_obstacle\": 100"))
        assertTrue(dict.contains("\"d_closest_wall\": 100"))
        assertTrue(dict.contains("\"boundingBox\":"))
        assertTrue(dict.contains("\"direction\":"))
    }

    @Test
    fun `dataObjectToPythonDict serializes linked objects`() {
        val turtle = mqp.getDataObjectById("turtle")
        val dict = mqp.dataObjectToPythonDict(turtle)
        // obstacles (linked list) should be serialized
        assertTrue(dict.contains("\"obstacles\":"))
        assertTrue(dict.contains("\"closest_obstacle\":"))
    }

    @Test
    fun `dataObjectToPythonDict serializes Wall correctly`() {
        val wall = mqp.getDataObjectById("wall_top")
        val dict = mqp.dataObjectToPythonDict(wall)
        assertTrue(dict.contains("\"p\":"))
        assertTrue(dict.contains("\"u_vec\":"))
        assertTrue(dict.contains("\"__type__\": \"Wall\""))
    }

    // ---- valueToPythonLiteral edge cases ----

    @Test
    fun `valueToPythonLiteral integer-like double`() {
        val result = mqp.valueToPythonLiteral(5.0, meta.SimplePropertyType.NUMBER)
        assertEquals("5", result)
    }

    @Test
    fun `valueToPythonLiteral fractional double`() {
        val result = mqp.valueToPythonLiteral(3.14, meta.SimplePropertyType.NUMBER)
        assertEquals("3.14", result)
    }

    @Test
    fun `valueToPythonLiteral zero`() {
        val result = mqp.valueToPythonLiteral(0.0, meta.SimplePropertyType.NUMBER)
        assertEquals("0", result)
    }

    @Test
    fun `valueToPythonLiteral negative number`() {
        val result = mqp.valueToPythonLiteral(-7.5, meta.SimplePropertyType.NUMBER)
        assertEquals("-7.5", result)
    }

    @Test
    fun `valueToPythonLiteral string with spaces`() {
        val result = mqp.valueToPythonLiteral("hello world", meta.SimplePropertyType.STRING)
        assertEquals("\"hello world\"", result)
    }

    @Test
    fun `valueToPythonLiteral boolean true`() {
        val result = mqp.valueToPythonLiteral(true, meta.SimplePropertyType.BOOLEAN)
        assertEquals("True", result)
    }

    @Test
    fun `valueToPythonLiteral boolean false`() {
        val result = mqp.valueToPythonLiteral(false, meta.SimplePropertyType.BOOLEAN)
        assertEquals("False", result)
    }

    // ---- anyToPythonLiteral edge cases ----

    @Test
    fun `anyToPythonLiteral for null`() {
        assertEquals("None", mqp.anyToPythonLiteral(null))
    }

    @Test
    fun `anyToPythonLiteral for DataObject`() {
        val pos = mqp.getDataObjectById("turtlePosition")
        val result = mqp.anyToPythonLiteral(pos)
        assertTrue(result.startsWith("{"))
        assertTrue(result.endsWith("}"))
        assertTrue(result.contains("\"x\":"))
    }

    @Test
    fun `anyToPythonLiteral for list of DataObjects`() {
        val turtle = mqp.getDataObjectById("turtle")
        val obstacles = mqp.resolvePathFromObject(turtle, "obstacles") as List<*>
        val result = mqp.anyToPythonLiteral(obstacles)
        assertTrue(result.startsWith("["))
        assertTrue(result.endsWith("]"))
        assertTrue(result.contains("chair"))
        assertTrue(result.contains("table"))
    }

    @Test
    fun `anyToPythonLiteral for empty list`() {
        val result = mqp.anyToPythonLiteral(emptyList<Any>())
        assertEquals("[]", result)
    }

    @Test
    fun `anyToPythonLiteral for list of mixed primitives`() {
        val result = mqp.anyToPythonLiteral(listOf(1.0, 2.0, 3.0))
        assertEquals("[1, 2, 3]", result)
    }

    @Test
    fun `anyToPythonLiteral for large integer-like double`() {
        assertEquals("1000000", mqp.anyToPythonLiteral(1000000.0))
    }

    @Test
    fun `anyToPythonLiteral for very small double`() {
        val result = mqp.anyToPythonLiteral(0.001)
        assertEquals("0.001", result)
    }
}




