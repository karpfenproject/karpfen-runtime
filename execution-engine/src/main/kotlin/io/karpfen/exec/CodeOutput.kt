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

/**
 * Receives the text that code of an `EVAL` block or a macro printed to stdout or stderr during a call. The text is not
 * part of the result of the call, so it needs a place of its own.
 */
fun interface CodeOutput {

    /** [text] was printed by code of the state machine instance on the object with the key [objectId]. */
    fun printed(objectId: String, text: String)

    companion object {

        /**
         * Writes the text to the console of the runtime, every line behind `[Python <objectId>] `. The lines of one
         * call are written at once, so the output of branches that run at the same time does not interleave inside a
         * call. One line break at the end of the text is dropped, because `print` writes it after the last line.
         */
        val CONSOLE = CodeOutput { objectId, text ->
            val prefix = "[Python $objectId] "
            val lines = text.removeSuffix("\n").lines()
            println(lines.joinToString("\n") { prefix + it })
        }
    }
}
