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

import instance.ClassTypeAtomicPropertyObject
import instance.DataObject
import instance.ObjectReference
import instance.SimpleAtomicPropertyObject
import instance.SimpleListPropertyObject
import io.karpfen.io.karpfen.exec.ModelJsonWriter
import meta.AssociationType
import meta.ClassType
import meta.ClassTypeProperty
import meta.ClassTypeReference
import meta.SimpleProperty
import meta.SimplePropertyType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Covers [ModelJsonWriter]: the object JSON of pushes and snapshots and the quoting of every string it writes.
 */
class ModelJsonWriterTest {

    @Test
    fun `the cleaning robot fixture is written byte for byte as before`() {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)

        val written = model.objects.joinToString("\n") { ModelJsonWriter.write(it) }

        assertEquals(CLEANING_ROBOT_JSON, written)
    }

    @Test
    fun `quotes backslashes and control characters in string values are escaped`() {
        val noteType = ClassType(
            "Note",
            "",
            listOf(
                SimpleProperty("text", SimplePropertyType.STRING, false),
                SimpleProperty("tags", SimplePropertyType.STRING, true)
            ),
            emptyList()
        )
        val note = DataObject(
            noteType,
            "note",
            mutableListOf(
                SimpleAtomicPropertyObject(
                    noteType.simpleProperties[0],
                    "text",
                    "say \"hi\" back\\slash \b \u000C \n \r \t \u0001 \u001F"
                ),
                SimpleListPropertyObject(noteType.simpleProperties[1], "tags", mutableListOf("a\"b", "c\\d"))
            ),
            mutableListOf()
        )

        val written = ModelJsonWriter.write(note)

        assertEquals(
            """{"__id__": "note", "__type__": "Note", "text": "say \"hi\" back\\slash \b \f \n \r \t \u0001 \u001f", "tags": ["a\"b", "c\\d"]}""",
            written
        )
    }

    @Test
    fun `ids type names and keys are escaped like string values`() {
        val innerType = ClassType("In\\ner", "", emptyList(), emptyList())
        val relation = ClassTypeProperty(
            "rel\"x",
            ClassTypeReference(AssociationType.EMBEDDED, "In\\ner", innerType),
            false,
            AssociationType.EMBEDDED
        )
        val outerType = ClassType(
            "Odd\"Type",
            "",
            listOf(SimpleProperty("we\"ird\tkey", SimplePropertyType.NUMBER, false)),
            listOf(relation)
        )
        val inner = DataObject(innerType, "inner\nid", mutableListOf(), mutableListOf())
        val outer = DataObject(
            outerType,
            "odd\"id",
            mutableListOf(SimpleAtomicPropertyObject(outerType.simpleProperties[0], "we\"ird\tkey", 1.5)),
            mutableListOf(ClassTypeAtomicPropertyObject(relation, "rel\"x", ObjectReference(inner.id, inner)))
        )

        val written = ModelJsonWriter.write(outer)

        assertEquals(
            """{"__id__": "odd\"id", "__type__": "Odd\"Type", "we\"ird\tkey": 1.5, "rel\"x": {"__id__": "inner\nid", "__type__": "In\\ner"}}""",
            written
        )
    }

    @Test
    fun `characters outside the control range are written unchanged`() {
        val text = "space \u0020 delete \u007F accent \u00e9 euro \u20ac line \u2028 " +
            "slash / angle </ emoji \uD83D\uDE00"

        assertEquals("\"" + text + "\"", ModelJsonWriter.quote(text))
    }

    private companion object {
        /** The fixture's root object as `ModelQueryProcessor.dataObjectToJson` wrote it before the writer existed. */
        val CLEANING_ROBOT_JSON: String = listOf(
            "{\"__id__\": \"APB 2101\", \"__type__\": \"Room\", \"robot\": ",
            "{\"__id__\": \"turtle\", \"__type__\": \"Robot\", \"d_closest_obstacle\": 100, \"d_closest_wall\": 100, \"boundingBox\": ",
            "{\"__id__\": \"turtleBoundingBox\", \"__type__\": \"TwoDObject\", \"diameter\": 0.2, \"position\": ",
            "{\"__id__\": \"turtlePosition\", \"__type__\": \"Vector\", \"x\": 5, \"y\": 5}}, \"direction\": ",
            "{\"__id__\": \"turtleDirection\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 1}, \"obstacles\": [",
            "{\"__id__\": \"chair\", \"__type__\": \"Obstacle\", \"boundingBox\": ",
            "{\"__id__\": \"chairBoundingBox\", \"__type__\": \"TwoDObject\", \"diameter\": 1, \"position\": ",
            "{\"__id__\": \"chairPosition\", \"__type__\": \"Vector\", \"x\": 2, \"y\": 3}}}, ",
            "{\"__id__\": \"table\", \"__type__\": \"Obstacle\", \"boundingBox\": ",
            "{\"__id__\": \"tableBoundingBox\", \"__type__\": \"TwoDObject\", \"diameter\": 3, \"position\": ",
            "{\"__id__\": \"tablePosition\", \"__type__\": \"Vector\", \"x\": 5, \"y\": 7}}}], \"walls\": [",
            "{\"__id__\": \"wall_top\", \"__type__\": \"Wall\", \"p\": ",
            "{\"__id__\": \"wallTopP\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 10}, \"u_vec\": ",
            "{\"__id__\": \"wallTopUVec\", \"__type__\": \"Vector\", \"x\": 1, \"y\": 0}}, ",
            "{\"__id__\": \"wall_right\", \"__type__\": \"Wall\", \"p\": ",
            "{\"__id__\": \"wallRightP\", \"__type__\": \"Vector\", \"x\": 10, \"y\": 0}, \"u_vec\": ",
            "{\"__id__\": \"wallRightUVec\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 1}}, ",
            "{\"__id__\": \"wall_bottom\", \"__type__\": \"Wall\", \"p\": ",
            "{\"__id__\": \"wallBottomP\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 0}, \"u_vec\": ",
            "{\"__id__\": \"wallBottomUVec\", \"__type__\": \"Vector\", \"x\": 1, \"y\": 0}}, ",
            "{\"__id__\": \"wall_left\", \"__type__\": \"Wall\", \"p\": ",
            "{\"__id__\": \"wallLeftP\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 0}, \"u_vec\": ",
            "{\"__id__\": \"wallLeftUVec\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 1}}], \"closest_obstacle\": ",
            "{\"__id__\": \"chair\", \"__type__\": \"Obstacle\", \"boundingBox\": ",
            "{\"__id__\": \"chairBoundingBox\", \"__type__\": \"TwoDObject\", \"diameter\": 1, \"position\": ",
            "{\"__id__\": \"chairPosition\", \"__type__\": \"Vector\", \"x\": 2, \"y\": 3}}}, \"closest_wall\": ",
            "{\"__id__\": \"wall_top\", \"__type__\": \"Wall\", \"p\": ",
            "{\"__id__\": \"wallTopP\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 10}, \"u_vec\": ",
            "{\"__id__\": \"wallTopUVec\", \"__type__\": \"Vector\", \"x\": 1, \"y\": 0}}}, \"obstacles\": [",
            "{\"__id__\": \"chair\", \"__type__\": \"Obstacle\", \"boundingBox\": ",
            "{\"__id__\": \"chairBoundingBox\", \"__type__\": \"TwoDObject\", \"diameter\": 1, \"position\": ",
            "{\"__id__\": \"chairPosition\", \"__type__\": \"Vector\", \"x\": 2, \"y\": 3}}}, ",
            "{\"__id__\": \"table\", \"__type__\": \"Obstacle\", \"boundingBox\": ",
            "{\"__id__\": \"tableBoundingBox\", \"__type__\": \"TwoDObject\", \"diameter\": 3, \"position\": ",
            "{\"__id__\": \"tablePosition\", \"__type__\": \"Vector\", \"x\": 5, \"y\": 7}}}], \"walls\": [",
            "{\"__id__\": \"wall_top\", \"__type__\": \"Wall\", \"p\": ",
            "{\"__id__\": \"wallTopP\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 10}, \"u_vec\": ",
            "{\"__id__\": \"wallTopUVec\", \"__type__\": \"Vector\", \"x\": 1, \"y\": 0}}, ",
            "{\"__id__\": \"wall_right\", \"__type__\": \"Wall\", \"p\": ",
            "{\"__id__\": \"wallRightP\", \"__type__\": \"Vector\", \"x\": 10, \"y\": 0}, \"u_vec\": ",
            "{\"__id__\": \"wallRightUVec\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 1}}, ",
            "{\"__id__\": \"wall_bottom\", \"__type__\": \"Wall\", \"p\": ",
            "{\"__id__\": \"wallBottomP\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 0}, \"u_vec\": ",
            "{\"__id__\": \"wallBottomUVec\", \"__type__\": \"Vector\", \"x\": 1, \"y\": 0}}, ",
            "{\"__id__\": \"wall_left\", \"__type__\": \"Wall\", \"p\": ",
            "{\"__id__\": \"wallLeftP\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 0}, \"u_vec\": ",
            "{\"__id__\": \"wallLeftUVec\", \"__type__\": \"Vector\", \"x\": 0, \"y\": 1}}]}"
        ).joinToString("")
    }
}
