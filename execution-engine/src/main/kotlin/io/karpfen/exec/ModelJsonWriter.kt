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

import instance.ClassTypeAtomicPropertyObject
import instance.ClassTypeListPropertyObject
import instance.DataObject
import instance.SimpleAtomicPropertyObject
import instance.SimpleListPropertyObject
import meta.SimplePropertyType
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Writes model objects as the JSON text the runtime pushes in `objectChanged` messages and answers in model
 * snapshots.
 *
 * An object is written as its entries joined with `", "`, each key followed by `": "`: first `"__id__"`, then
 * `"__type__"`, then every simple property present on the instance in instance order, then every relation present
 * on the instance in instance order. `has` and `knows` relations are both written as full nested objects; an empty
 * atomic relation is `null`, and an unresolved entry of a list relation is `null`. Numbers are doubles, written
 * without a fraction when integral and with [Double.toString] otherwise, so `NaN`, `Infinity` and `-Infinity`
 * appear as bare literals. Ids, type names, keys and string values go through [quote].
 *
 * Because `knows` relations are inlined like `has` relations, an object reachable from itself has no finite JSON. The
 * writer tracks the objects on the current path by identity and refuses such a loop with an [IllegalStateException]
 * instead of recursing until the stack overflows, which as an [Error] would end the engine thread. Inside the engine
 * the exception leaves the commit of the action block, is reported as an action error, and the engine keeps ticking.
 */
object ModelJsonWriter {

    /**
     * Writes [obj] with everything reachable through its relations as one JSON object.
     *
     * Every text that was valid JSON when string values only had their double quotes escaped is written byte for
     * byte as before. An object shared by several relations without a loop is written once per reference.
     *
     * @throws IllegalStateException when an object is reachable from itself, naming the first object met twice.
     */
    fun write(obj: DataObject): String = writeObject(obj, Collections.newSetFromMap(IdentityHashMap()))

    /**
     * Writes [text] as a JSON string literal in double quotes.
     *
     * Exactly `"`, `\` and the control characters U+0000 to U+001F are escaped: `\b`, `\f`, `\n`, `\r` and `\t` by
     * their short forms, the others as `\u00XX` with lower-case hex digits. Every other character is written as it
     * is.
     */
    fun quote(text: String): String {
        val literal = StringBuilder(text.length + 2)
        literal.append('"')
        for (character in text) {
            when (character) {
                '"' -> literal.append("\\\"")
                '\\' -> literal.append("\\\\")
                '\b' -> literal.append("\\b")
                '\u000C' -> literal.append("\\f")
                '\n' -> literal.append("\\n")
                '\r' -> literal.append("\\r")
                '\t' -> literal.append("\\t")
                else -> if (character < ' ') {
                    literal.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    literal.append(character)
                }
            }
        }
        literal.append('"')
        return literal.toString()
    }

    /**
     * Writes one object and, recursively, the objects its relations hold; [path] holds the objects being written
     * around it.
     */
    private fun writeObject(obj: DataObject, path: MutableSet<DataObject>): String {
        if (!path.add(obj)) {
            throw IllegalStateException("Reference loop through object '${obj.id}' cannot be written as JSON")
        }
        val entries = mutableListOf<String>()
        entries.add(entry("__id__", quote(obj.id)))
        entries.add(entry("__type__", quote(obj.ofType.name)))

        for (prop in obj.properties) {
            if (prop.isList()) {
                val values = (prop as SimpleListPropertyObject).getValues()
                    .joinToString(", ") { writeValue(it, prop.getValueType()) }
                entries.add(entry(prop.key, "[$values]"))
            } else {
                val value = (prop as SimpleAtomicPropertyObject).getValue()
                entries.add(entry(prop.key, writeValue(value, prop.getValueType())))
            }
        }

        for (rel in obj.relations) {
            if (rel.isList()) {
                val values = (rel as ClassTypeListPropertyObject).getValueRefs()
                    .joinToString(", ") { ref -> ref.obj?.let { writeObject(it, path) } ?: "null" }
                entries.add(entry(rel.key, "[$values]"))
            } else {
                val value = (rel as ClassTypeAtomicPropertyObject).getValue()
                entries.add(entry(rel.key, if (value != null) writeObject(value, path) else "null"))
            }
        }

        path.remove(obj)
        return "{${entries.joinToString(", ")}}"
    }

    /** Writes one member of an object: the quoted [key], `": "` and the already written [value]. */
    private fun entry(key: String, value: String): String = "${quote(key)}: $value"

    /** Writes one simple property value of the given [type]; numbers are stored as doubles in the model. */
    private fun writeValue(value: Any, type: SimplePropertyType): String {
        return when (type) {
            SimplePropertyType.STRING -> quote(value.toString())
            SimplePropertyType.NUMBER -> {
                val d = value as Double
                if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
            }
            SimplePropertyType.BOOLEAN -> if (value as Boolean) "true" else "false"
        }
    }
}
