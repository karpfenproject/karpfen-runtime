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

import io.karpfen.io.karpfen.exec.ModelJsonWriter
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import io.karpfen.io.karpfen.exec.PythonLiteralWriter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers [PythonLiteralWriter] without running Python: the quoting of strings, the special numbers, and objects that
 * know each other, which the writer refuses like [ModelJsonWriter] does.
 */
class PythonLiteralWriterTest {

    private val metamodel = PythonFixtures.metamodel()
    private val model = PythonFixtures.model(metamodel)
    private val mqp = ModelQueryProcessor(metamodel, model)

    @Test
    fun `quotes, backslashes and control characters are escaped and every other character stays`() {
        val text = "say \"hi\" it's back\\slash \b \u000C \n \r \t \u0001 \u001F \u007F Grüße 💡  "

        assertEquals(
            "\"say \\\"hi\\\" it's back\\\\slash \\b \\f \\n \\r \\t \\x01 \\x1f \\x7f Grüße 💡  \"",
            PythonLiteralWriter.quote(text)
        )
    }

    @Test
    fun `numbers are written as Python reads them back, the special floats included`() {
        assertEquals("40", PythonLiteralWriter.number(40.0))
        assertEquals("-0.5", PythonLiteralWriter.number(-0.5))
        assertEquals("1.0E300", PythonLiteralWriter.number(1e300))
        assertEquals("float(\"nan\")", PythonLiteralWriter.number(Double.NaN))
        assertEquals("float(\"inf\")", PythonLiteralWriter.number(Double.POSITIVE_INFINITY))
        assertEquals("-float(\"inf\")", PythonLiteralWriter.number(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `an object is a dictionary with id, type, properties and relations in instance order`() {
        mqp.setScalar("bulbA", "label", "a \"b\"")

        assertEquals(
            "{\"__id__\": \"bulbA\", \"__type__\": \"Bulb\", \"label\": \"a \\\"b\\\"\"}",
            mqp.anyToPythonLiteral(mqp.getDataObjectById("bulbA"))
        )
    }

    @Test
    fun `two objects that know each other are refused, naming the object met twice`() {
        mqp.appendObjectRelation("a", "partners", mqp.getDataObjectById("b"))
        mqp.appendObjectRelation("b", "partners", mqp.getDataObjectById("a"))

        val refusal = assertFailsWith<IllegalStateException> { mqp.anyToPythonLiteral(mqp.getDataObjectById("a")) }
        assertEquals("Reference loop through object 'a' cannot be handed to code", refusal.message)
        assertFailsWith<IllegalStateException> { ModelJsonWriter.write(mqp.getDataObjectById("a")) }
    }

    @Test
    fun `a longer loop over knows and has relations is refused, also inside a list`() {
        mqp.appendObjectRelation("a", "partners", mqp.getDataObjectById("b"))
        mqp.appendObjectRelation("b", "partners", mqp.getDataObjectById("c"))
        mqp.appendObjectRelation("bulbC", "owners", mqp.getDataObjectById("a"))

        val refusal = assertFailsWith<IllegalStateException> { mqp.anyToPythonLiteral(mqp.getDataObjectById("room")) }
        assertEquals("Reference loop through object 'a' cannot be handed to code", refusal.message)
        assertFailsWith<IllegalStateException> {
            mqp.anyToPythonLiteral(mqp.resolvePathFromObject(mqp.getDataObjectById("room"), "lamps"))
        }
    }

    @Test
    fun `an object known by several objects without a loop is written once per reference`() {
        mqp.appendObjectRelation("a", "partners", mqp.getDataObjectById("c"))
        mqp.appendObjectRelation("b", "partners", mqp.getDataObjectById("c"))
        mqp.appendObjectRelation("bulbA", "owners", mqp.getDataObjectById("b"))

        val room = mqp.anyToPythonLiteral(mqp.getDataObjectById("room"))

        // c is in the list of the room, the partner of a and of b, and of b again where bulbA's holder is written.
        assertEquals(4, Regex("\"__id__\": \"c\"").findAll(room).count())
    }

    @Test
    fun `the values inside a loop stay readable by their paths`() {
        mqp.appendObjectRelation("a", "partners", mqp.getDataObjectById("b"))
        mqp.appendObjectRelation("b", "partners", mqp.getDataObjectById("a"))

        val label = mqp.resolvePathFromObject(mqp.getDataObjectById("a"), "partners->partners->label")

        assertEquals("\"plain\"", mqp.anyToPythonLiteral(label))
    }
}
