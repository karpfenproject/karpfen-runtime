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
 * Rewrites Python-like DSL code (from EVAL/DEFINITION blocks) into properly
 * indented Python code, using tabs as indentation.
 *
 * The DSL code uses explicit block-end keywords to close blocks, making it
 * possible to compute indentation from scratch regardless of how the code was
 * originally indented in the .kstates file:
 *
 *   - `endif`   – closes an `if`/`else`/`elif` block
 *   - `endfor`  – closes a `for` block
 *   - `endwhile`– closes a `while` block
 *
 * `else` and `elif` are treated as *same-level* closers followed by a new
 * opener: the depth is decremented, the line is emitted (with its trailing `:`),
 * and then the depth is incremented again.
 *
 * Block-opening lines are those whose stripped content ends with `:`.
 * Block-end keywords (`endif`, `endfor`, `endwhile`) are consumed and never
 * emitted into the output.
 *
 * All output lines are indented with `\t` characters at the computed depth.
 */
object PythonIndentationRewriter {

    /** Keywords that close the current indentation block (consumed, not emitted). */
    private val BLOCK_END_KEYWORDS = setOf("endif", "endfor", "endwhile")

    /**
     * Rewrites [code] into properly tab-indented Python, starting at indentation
     * level [baseDepth] (number of leading tabs).
     *
     * @param code        Raw code from the DSL, any original indentation is ignored.
     * @param baseDepth   The indent level for the outermost lines (default 0).
     * @return            Rewritten Python code as a single string (lines joined by `\n`).
     */
    fun rewrite(code: String, baseDepth: Int = 0): String {
        val output = mutableListOf<String>()
        var depth = baseDepth

        for (rawLine in code.lines()) {
            val line = rawLine.trim()

            // Skip blank lines and preserve them without indentation
            if (line.isEmpty()) {
                output.add("")
                continue
            }

            // Skip comment-only lines — preserve them at current depth
            if (line.startsWith("#")) {
                output.add("\t".repeat(depth) + line)
                continue
            }

            // Block-end keywords: close the current block (decrement depth, do NOT emit)
            val keyword = line.lowercase()
            if (keyword in BLOCK_END_KEYWORDS) {
                if (depth > baseDepth) depth--
                continue
            }

            // `else` / `elif`: close the previous branch (decrement), emit, open the next (increment)
            if (keyword == "else:" || keyword.startsWith("elif ") || keyword.startsWith("else:")) {
                if (depth > baseDepth) depth--
                output.add("\t".repeat(depth) + line)
                depth++
                continue
            }

            // Normal line: emit at current depth, then check if it opens a new block
            output.add("\t".repeat(depth) + line)

            // A line ending with `:` opens a new block (if, for, while, def, with, try, except, finally, class)
            if (line.endsWith(":")) {
                depth++
            }
        }

        return output.joinToString("\n")
    }
}

