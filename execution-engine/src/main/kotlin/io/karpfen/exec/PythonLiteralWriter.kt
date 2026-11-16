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
package io.karpfen.io.karpfen.exec

import io.karpfen.dsl.instance.ClassTypeAtomicPropertyObject
import io.karpfen.dsl.instance.ClassTypeListPropertyObject
import io.karpfen.dsl.instance.DataObject
import io.karpfen.dsl.instance.SimpleAtomicPropertyObject
import io.karpfen.dsl.instance.SimpleListPropertyObject
import io.karpfen.dsl.meta.SimplePropertyType
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Writes model values as Python literals, the form in which `$(path)` and the arguments of macros enter the code of an
 * `EVAL` or a macro.
 *
 * - `null` is `None`, a boolean `True` or `False`.
 * - A number is written without a fraction when it is integral and fits a long, and with [Double.toString] otherwise.
 *   `NaN`, `Infinity` and `-Infinity` are written as `float("nan")`, `float("inf")` and `-float("inf")`, which Python
 *   reads back as the same values.
 * - A string goes through [quote], so every character arrives in Python as it is in the model.
 * - A list is a Python list of its elements.
 * - An object is a dictionary with the entries `"__id__"`, `"__type__"`, every simple property and every relation
 *   present on the instance, in instance order. `has` and `knows` relations are both written as nested dictionaries;
 *   an empty atomic relation is `None`.
 *
 * Objects follow the rule of [ModelJsonWriter]: an object shared by several relations without a loop is written once
 * per reference, and an object that is reachable from itself has no finite literal. The writer tracks the objects on
 * the current path by identity and refuses such a loop with an [IllegalStateException] that names the first object met
 * twice, instead of recursing until the stack overflows. The exception is an error of the `EVAL` or the macro that
 * reads the value: an action fails, a condition is false, and the engine goes on.
 *
 * The writer reads the objects as they are; the caller holds the read lock of the model.
 */
object PythonLiteralWriter {

    /**
     * Writes [value], a value as paths resolve it: `null`, a [DataObject], a list, a string, a boolean or a number.
     * Any other value is written as the string of its [toString].
     *
     * @throws IllegalStateException when an object is reachable from itself.
     */
    fun write(value: Any?): String = writeAny(value, newPath())

    /**
     * Writes one simple property value of the given [type]; numbers are stored as doubles in the model.
     */
    fun writeSimple(value: Any, type: SimplePropertyType): String = when (type) {
        SimplePropertyType.STRING -> quote(value.toString())
        SimplePropertyType.NUMBER -> number(value as Double)
        SimplePropertyType.BOOLEAN -> if (value as Boolean) "True" else "False"
    }

    /**
     * Writes [text] as a Python string literal in double quotes.
     *
     * `"` and `\` are escaped with a backslash, the control characters U+0000 to U+001F and U+007F by their short forms
     * `\b`, `\t`, `\n`, `\f`, `\r` or as `\x` and two hex digits. Every other character, also every character outside
     * ASCII, is written as it is; the code reaches Python as UTF-8.
     */
    fun quote(text: String): String {
        val literal = StringBuilder(text.length + 2)
        literal.append('"')
        for (character in text) {
            when (character) {
                '"' -> literal.append("\\\"")
                '\\' -> literal.append("\\\\")
                '\b' -> literal.append("\\b")
                '\t' -> literal.append("\\t")
                '\n' -> literal.append("\\n")
                '\u000C' -> literal.append("\\f")
                '\r' -> literal.append("\\r")
                else -> if (character < ' ' || character == '\u007F') {
                    literal.append("\\x").append(character.code.toString(16).padStart(2, '0'))
                } else {
                    literal.append(character)
                }
            }
        }
        literal.append('"')
        return literal.toString()
    }

    /** Writes the number [d]; see the class description. */
    fun number(d: Double): String = when {
        d.isNaN() -> "float(\"nan\")"
        d == Double.POSITIVE_INFINITY -> "float(\"inf\")"
        d == Double.NEGATIVE_INFINITY -> "-float(\"inf\")"
        d == d.toLong().toDouble() -> d.toLong().toString()
        else -> d.toString()
    }

    private fun newPath(): MutableSet<DataObject> = Collections.newSetFromMap(IdentityHashMap())

    private fun writeAny(value: Any?, path: MutableSet<DataObject>): String = when (value) {
        null -> "None"
        is DataObject -> writeObject(value, path)
        is List<*> -> value.joinToString(", ", "[", "]") { writeAny(it, path) }
        is String -> quote(value)
        is Boolean -> if (value) "True" else "False"
        is Double -> number(value)
        is Number -> value.toString()
        else -> quote(value.toString())
    }

    /**
     * Writes one object and, recursively, the objects its relations hold; [path] holds the objects being written
     * around it.
     */
    private fun writeObject(obj: DataObject, path: MutableSet<DataObject>): String {
        if (!path.add(obj)) {
            throw IllegalStateException("Reference loop through object '${obj.id}' cannot be handed to code")
        }
        val entries = mutableListOf<String>()
        entries.add("\"__id__\": ${quote(obj.id)}")
        entries.add("\"__type__\": ${quote(obj.ofType.name)}")

        for (prop in obj.properties) {
            val value = if (prop.isList()) {
                (prop as SimpleListPropertyObject).getValues()
                    .joinToString(", ", "[", "]") { writeSimple(it, prop.getValueType()) }
            } else {
                writeSimple((prop as SimpleAtomicPropertyObject).getValue(), prop.getValueType())
            }
            entries.add("${quote(prop.key)}: $value")
        }

        for (rel in obj.relations) {
            val value = if (rel.isList()) {
                (rel as ClassTypeListPropertyObject).getValues().joinToString(", ", "[", "]") { writeObject(it, path) }
            } else {
                (rel as ClassTypeAtomicPropertyObject).getValue()?.let { writeObject(it, path) } ?: "None"
            }
            entries.add("${quote(rel.key)}: $value")
        }

        path.remove(obj)
        return "{${entries.joinToString(", ")}}"
    }
}
