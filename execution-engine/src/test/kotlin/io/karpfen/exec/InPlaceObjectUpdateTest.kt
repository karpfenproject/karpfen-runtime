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

import io.karpfen.dsl.dsl.textual.KmetaDSLConverter
import io.karpfen.dsl.dsl.textual.KmodelDSLConverter
import io.karpfen.dsl.instance.DataObject
import io.karpfen.io.karpfen.exec.MacroProcessor
import io.karpfen.io.karpfen.exec.ModelChangePublisher
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import io.karpfen.io.karpfen.exec.ModelStructureListener
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A dictionary with the `__id__` of an existing object, as an `EVAL` or a macro returns it, changes that object in
 * place: objects its `has` relations no longer hold leave the model like through `DROPOBJ`, and objects they newly hold
 * enter it as new objects. The tests parse the dictionaries with [MacroProcessor.parseResult], the same way the result
 * of Python is parsed, so they need no Python.
 */
class InPlaceObjectUpdateTest {

    private val metamodel = KmetaDSLConverter.parseKmetaString(METAMODEL)
    private val model = KmodelDSLConverter.parseKmodelString(MODEL, metamodel)
    private val queries = ModelQueryProcessor(metamodel, model)
    private val macros = MacroProcessor(metamodel, model, emptyList(), "room", queries)

    /** What the model told its listeners: `+id` for an added object, `-id` for a removed one, in order. */
    private val structure = ArrayList<String>()

    /** What the model published: `~id` for a change, `x id` for a deletion, in order. */
    private val published = ArrayList<String>()

    init {
        queries.addStructureListener(object : ModelStructureListener {
            override fun objectsAdded(objects: List<DataObject>) {
                objects.forEach { structure += "+${it.id}" }
            }

            override fun objectsRemoved(objectIds: Set<String>) {
                objectIds.forEach { structure += "-$it" }
            }
        })
        queries.addChangePublisher(object : ModelChangePublisher {
            override fun onObjectChanged(objectId: String, jsonValue: String) {
                published += "~$objectId"
            }

            override fun onObjectDeleted(objectId: String) {
                published += "x $objectId"
            }
        })
    }

    @Test
    fun `a list relation that drops an object removes it and a new object in it enters the model`() {
        val b3 = queries.getDataObjectById("b3")

        val room = parse("""{"__id__": "room", "spares": [{"__id__": "b3"}, {"log": ["new"]}]}""", "Room")

        assertSame(queries.getDataObjectById("room"), room)
        assertFalse(queries.containsObject("b2"))
        assertTrue(queries.containsObject("gen_Bulb_0"))
        assertEquals(listOf("-b2", "+gen_Bulb_0"), structure)
        assertEquals(listOf("b3", "gen_Bulb_0"), relation("room", "spares"))
        assertSame(b3, queries.getDataObjectById("b3"))
        assertEquals(emptyList(), relation("room", "favourite"), "the knows reference to the removed bulb is gone")
        assertEquals(listOf("~b3", "~room", "x b2"), published)
    }

    @Test
    fun `a single relation given a new object removes the old one with everything it holds`() {
        parse("""{"__id__": "lamp", "bulb": {"log": []}}""", "Lamp")

        assertFalse(queries.containsObject("b1"))
        assertEquals(listOf("-b1", "+gen_Bulb_0"), structure)
        assertEquals(listOf("gen_Bulb_0"), relation("lamp", "bulb"))
    }

    @Test
    fun `properties alone change the object without removing anything`() {
        parse("""{"__id__": "b2", "watts": 5}""", "Bulb")

        assertEquals(emptyList(), structure)
        assertEquals(listOf<Any>(5.0), queries.getDataObjectById("b2").getProp("watts"))
        assertEquals(listOf("~b2"), published)
    }

    @Test
    fun `a has relation cannot take an object that another object holds`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            parse("""{"__id__": "room", "spares": [{"__id__": "b1"}]}""", "Room")
        }

        assertEquals(
            "Relation 'spares' is a 'has' relation and can only keep the objects it holds, but 'b1' is held elsewhere " +
                "in the model",
            refusal.message
        )
        assertTrue(queries.containsObject("b2"))
        assertEquals(emptyList(), structure.filter { it.startsWith("-") })
    }

    /** Parses [json] as the result for the type [typeName] while holding the write lock, as an action does. */
    private fun parse(json: String, typeName: String): DataObject = queries.writeModel {
        queries.beginBatch()
        val result = macros.parseResult(json, typeName) as DataObject
        queries.commitBatch()
        result
    }

    /** The keys of the objects in the relation [key] of [objectId]. */
    private fun relation(objectId: String, key: String): List<String> {
        return queries.getDataObjectById(objectId).getRel(key).map { it.id }
    }

    private companion object {
        val METAMODEL = """
            type "Room" "A room with a lamp and spare bulbs" {
                prop("log", list("string"))
                has("lamp", "Lamp")
                has("spares", list("Bulb"))
                knows("favourite", list("Bulb"))
            }
            type "Lamp" "A lamp with a bulb" {
                prop("log", list("string"))
                has("bulb", "Bulb")
            }
            type "Bulb" "A light bulb" {
                prop("log", list("string"))
                prop("watts", "number")
            }
        """.trimIndent()

        val MODEL = """
            make object "room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" {
                    has("bulb") -> make object "b1":"Bulb" { }
                }
                has("spares") -> make object "b2":"Bulb" {
                    prop("watts") -> "0"
                }
                has("spares") -> make object "b3":"Bulb" { }
                knows("favourite") -> "b2"
            }
        """.trimIndent()
    }
}
