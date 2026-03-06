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

import io.karpfen.io.karpfen.exec.ModelChangePublisher
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModelQueryProcessorObserverTest {

    private lateinit var mqp: ModelQueryProcessor

    @BeforeEach
    fun setUp() {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        mqp = ModelQueryProcessor(metamodel, model)
    }

    // ---- updateProperty + observer notification ----

    @Test
    fun `updateProperty changes value on DataObject`() {
        mqp.updateProperty("turtle", "d_closest_obstacle", 42.0)
        val obj = mqp.getDataObjectById("turtle")
        assertEquals(42.0, obj.getProp("d_closest_obstacle").first())
    }

    @Test
    fun `updateProperty notifies registered publisher`() {
        var notifiedId: String? = null
        var notifiedJson: String? = null
        mqp.addChangePublisher { id, json ->
            notifiedId = id
            notifiedJson = json
        }

        mqp.updateProperty("turtle", "d_closest_wall", 7.5)

        assertEquals("turtle", notifiedId)
        assertNotNull(notifiedJson)
        assertTrue(notifiedJson!!.contains("\"d_closest_wall\""))
    }

    @Test
    fun `updateProperty notifies all registered publishers`() {
        val collected = mutableListOf<String>()
        mqp.addChangePublisher { id, _ -> collected.add("pub1:$id") }
        mqp.addChangePublisher { id, _ -> collected.add("pub2:$id") }

        mqp.updateProperty("turtle", "d_closest_obstacle", 99.0)

        assertEquals(2, collected.size)
        assertTrue(collected.contains("pub1:turtle"))
        assertTrue(collected.contains("pub2:turtle"))
    }

    @Test
    fun `updateProperties changes multiple values and notifies once`() {
        var callCount = 0
        mqp.addChangePublisher { _, _ -> callCount++ }

        mqp.updateProperties("turtle", mapOf(
            "d_closest_obstacle" to 10.0,
            "d_closest_wall" to 20.0
        ))

        val obj = mqp.getDataObjectById("turtle")
        assertEquals(10.0, obj.getProp("d_closest_obstacle").first())
        assertEquals(20.0, obj.getProp("d_closest_wall").first())
        assertEquals(1, callCount)
    }

    @Test
    fun `removeChangePublisher stops notifications`() {
        var callCount = 0
        val publisher = ModelChangePublisher { _, _ -> callCount++ }
        mqp.addChangePublisher(publisher)
        mqp.updateProperty("turtle", "d_closest_obstacle", 1.0)
        assertEquals(1, callCount)

        mqp.removeChangePublisher(publisher)
        mqp.updateProperty("turtle", "d_closest_obstacle", 2.0)
        assertEquals(1, callCount) // no additional call
    }

    @Test
    fun `updateRelation changes relation and notifies publisher`() {
        var notifiedId: String? = null
        mqp.addChangePublisher { id, _ -> notifiedId = id }

        val tableObj = mqp.getDataObjectById("table")
        mqp.updateRelation("turtle", "closest_obstacle", tableObj)

        assertEquals("turtle", notifiedId)
        val updatedRel = mqp.getDataObjectById("turtle").getRel("closest_obstacle")
        assertEquals(1, updatedRel.size)
        assertEquals("table", updatedRel.first().id)
    }

    // ---- dataObjectToJson ----

    @Test
    fun `dataObjectToJson produces valid JSON with __id__ and __type__`() {
        val json = mqp.dataObjectToJson(mqp.getDataObjectById("turtle"))
        assertTrue(json.startsWith("{"))
        assertTrue(json.contains("\"__id__\": \"turtle\""))
        assertTrue(json.contains("\"__type__\": \"Robot\""))
    }

    @Test
    fun `dataObjectToJson includes simple properties`() {
        val json = mqp.dataObjectToJson(mqp.getDataObjectById("turtle"))
        assertTrue(json.contains("\"d_closest_obstacle\""))
        assertTrue(json.contains("\"d_closest_wall\""))
    }

    @Test
    fun `dataObjectToJson includes nested embedded objects`() {
        val json = mqp.dataObjectToJson(mqp.getDataObjectById("turtlePosition"))
        assertTrue(json.contains("\"x\""))
        assertTrue(json.contains("\"y\""))
        assertTrue(json.contains("5"))
    }

    @Test
    fun `dataObjectToJson for Vector is correct`() {
        val vec = mqp.getDataObjectById("turtleDirection")
        val json = mqp.dataObjectToJson(vec)
        assertTrue(json.contains("\"__id__\": \"turtleDirection\""))
        assertTrue(json.contains("\"__type__\": \"Vector\""))
        assertTrue(json.contains("\"x\""))
        assertTrue(json.contains("\"y\""))
    }

    @Test
    fun `observer receives valid JSON on update`() {
        var receivedJson = ""
        mqp.addChangePublisher { _, json -> receivedJson = json }

        mqp.updateProperty("turtlePosition", "x", 9.9)

        assertTrue(receivedJson.contains("\"x\""))
        // The JSON should be parseable (starts with {)
        assertTrue(receivedJson.startsWith("{"))
    }

    // ---- updateProperty on nested object ----

    @Test
    fun `updateProperty on nested object notifies with that object id`() {
        var notifiedId: String? = null
        mqp.addChangePublisher { id, _ -> notifiedId = id }

        mqp.updateProperty("turtlePosition", "x", 3.0)

        assertEquals("turtlePosition", notifiedId)
        val pos = mqp.getDataObjectById("turtlePosition")
        assertEquals(3.0, pos.getProp("x").first())
    }

    // ---- Thread-safe notification ----

    @Test
    fun `concurrent updateProperty is safe`() {
        val counts = java.util.concurrent.atomic.AtomicInteger(0)
        mqp.addChangePublisher { _, _ -> counts.incrementAndGet() }

        val threads = (1..10).map { i ->
            Thread { mqp.updateProperty("turtle", "d_closest_obstacle", i.toDouble()) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(10, counts.get())
    }
}


