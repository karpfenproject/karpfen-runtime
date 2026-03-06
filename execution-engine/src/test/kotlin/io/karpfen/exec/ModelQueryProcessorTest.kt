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

class ModelQueryProcessorTest {

    private lateinit var mqp: ModelQueryProcessor

    @BeforeEach
    fun setUp() {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        mqp = ModelQueryProcessor(metamodel, model)
    }

    // ---- getDataObjectById ----

    @Test
    fun `getDataObjectById returns root object`() {
        val room = mqp.getDataObjectById("APB 2101")
        assertEquals("Room", room.ofType.name)
        assertEquals("APB 2101", room.id)
    }

    @Test
    fun `getDataObjectById returns nested embedded object`() {
        val pos = mqp.getDataObjectById("turtlePosition")
        assertEquals("Vector", pos.ofType.name)
    }

    @Test
    fun `getDataObjectById returns linked object`() {
        val chair = mqp.getDataObjectById("chair")
        assertEquals("Obstacle", chair.ofType.name)
    }

    @Test
    fun `getDataObjectById throws on unknown id`() {
        assertThrows(IllegalArgumentException::class.java) {
            mqp.getDataObjectById("nonExistentId")
        }
    }

    // ---- getValueOfThing ----

    @Test
    fun `getValueOfThing returns DataObject for single segment relation`() {
        val result = mqp.getValueOfThing("turtle", listOf("boundingBox"))
        assertTrue(result is DataObject)
        assertEquals("turtleBoundingBox", (result as DataObject).id)
    }

    @Test
    fun `getValueOfThing traverses embedded objects`() {
        val result = mqp.getValueOfThing("turtle", listOf("boundingBox", "position"))
        assertTrue(result is DataObject)
        assertEquals("turtlePosition", (result as DataObject).id)
    }

    @Test
    fun `getValueOfThing returns simple property value at end of path`() {
        val result = mqp.getValueOfThing("turtle", listOf("boundingBox", "position", "x"))
        assertEquals(5.0, result)
    }

    @Test
    fun `getValueOfThing returns simple property directly`() {
        val result = mqp.getValueOfThing("turtle", listOf("d_closest_obstacle"))
        assertEquals(100.0, result)
    }

    @Test
    fun `getValueOfThing returns list of linked objects`() {
        val result = mqp.getValueOfThing("turtle", listOf("obstacles"))
        assertTrue(result is List<*>)
        val list = result as List<*>
        assertEquals(2, list.size)
        assertTrue(list.all { it is DataObject })
    }

    @Test
    fun `getValueOfThing returns single linked object`() {
        val result = mqp.getValueOfThing("turtle", listOf("closest_obstacle"))
        assertTrue(result is DataObject)
        assertEquals("chair", (result as DataObject).id)
    }

    @Test
    fun `getValueOfThing navigates through linked object`() {
        val result = mqp.getValueOfThing("turtle", listOf("closest_obstacle", "boundingBox", "position", "x"))
        assertEquals(2.0, result)
    }

    @Test
    fun `getValueOfThing returns DataObject when path is empty`() {
        val result = mqp.getValueOfThing("turtle", emptyList())
        assertTrue(result is DataObject)
        assertEquals("turtle", (result as DataObject).id)
    }

    @Test
    fun `getValueOfThing throws on invalid segment`() {
        assertThrows(IllegalArgumentException::class.java) {
            mqp.getValueOfThing("turtle", listOf("nonExistentProp"))
        }
    }

    @Test
    fun `getValueOfThing throws when navigating through simple property`() {
        assertThrows(IllegalArgumentException::class.java) {
            mqp.getValueOfThing("turtle", listOf("d_closest_obstacle", "something"))
        }
    }

    // ---- resolvePathFromObject ----

    @Test
    fun `resolvePathFromObject resolves simple property`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = mqp.resolvePathFromObject(turtle, "d_closest_obstacle")
        assertEquals(100.0, result)
    }

    @Test
    fun `resolvePathFromObject resolves nested path`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = mqp.resolvePathFromObject(turtle, "boundingBox->position->x")
        assertEquals(5.0, result)
    }

    @Test
    fun `resolvePathFromObject resolves to DataObject`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = mqp.resolvePathFromObject(turtle, "boundingBox->position")
        assertTrue(result is DataObject)
        assertEquals("turtlePosition", (result as DataObject).id)
    }

    @Test
    fun `resolvePathFromObject resolves linked object chain`() {
        val turtle = mqp.getDataObjectById("turtle")
        val result = mqp.resolvePathFromObject(turtle, "closest_obstacle->boundingBox->position->x")
        assertEquals(2.0, result)
    }

    // ---- dataObjectToPythonDict ----

    @Test
    fun `dataObjectToPythonDict converts simple Vector`() {
        val pos = mqp.getDataObjectById("turtlePosition")
        val dict = mqp.dataObjectToPythonDict(pos)
        assertTrue(dict.contains("\"x\": 5"))
        assertTrue(dict.contains("\"y\": 5"))
        assertTrue(dict.contains("\"__id__\": \"turtlePosition\""))
        assertTrue(dict.contains("\"__type__\": \"Vector\""))
    }

    @Test
    fun `dataObjectToPythonDict converts nested object`() {
        val bb = mqp.getDataObjectById("turtleBoundingBox")
        val dict = mqp.dataObjectToPythonDict(bb)
        assertTrue(dict.contains("\"diameter\": 0.2"))
        assertTrue(dict.contains("\"position\":"))
        assertTrue(dict.contains("\"__id__\": \"turtlePosition\""))
    }

    // ---- anyToPythonLiteral ----

    @Test
    fun `anyToPythonLiteral for number`() {
        assertEquals("5", mqp.anyToPythonLiteral(5.0))
        assertEquals("5.5", mqp.anyToPythonLiteral(5.5))
    }

    @Test
    fun `anyToPythonLiteral for boolean`() {
        assertEquals("True", mqp.anyToPythonLiteral(true))
        assertEquals("False", mqp.anyToPythonLiteral(false))
    }

    @Test
    fun `anyToPythonLiteral for string`() {
        assertEquals("\"hello\"", mqp.anyToPythonLiteral("hello"))
    }

    @Test
    fun `anyToPythonLiteral for null`() {
        assertEquals("None", mqp.anyToPythonLiteral(null))
    }

    @Test
    fun `anyToPythonLiteral for DataObject`() {
        val pos = mqp.getDataObjectById("turtlePosition")
        val result = mqp.anyToPythonLiteral(pos)
        assertTrue(result.startsWith("{"))
        assertTrue(result.contains("\"x\":"))
    }

    @Test
    fun `anyToPythonLiteral for list`() {
        val result = mqp.anyToPythonLiteral(listOf(1.0, 2.0, 3.0))
        assertEquals("[1, 2, 3]", result)
    }
}


