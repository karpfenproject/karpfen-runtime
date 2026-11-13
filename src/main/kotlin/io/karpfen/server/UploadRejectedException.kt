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
package io.karpfen.server

import io.karpfen.dsl.diagnostics.Diagnostic

/**
 * The refusal of an uploaded text whose read found errors.
 *
 * It is an [IllegalArgumentException], so [HTTPRoutes.respondWithError] answers it with `400 Bad Request` and its
 * [message] as the body. The message has a first line that names the upload and counts its errors, followed by one
 * line per error as [placedLine] writes it, in the order of [errors]. The lines are separated by a line feed, and
 * the message does not end with one.
 *
 * ```
 * Upload of the metamodel rejected: 2 errors
 * 2:5: mismatched input 'prop' expecting ... (syntax.error)
 * 5:10: ... (kmeta.unknown-target-type)
 * ```
 *
 * @property upload What was uploaded, in words that complete "Upload of the ...", such as `metamodel` or
 * `state machine for 'robot'`.
 * @property errors The errors of the read, at least one, ordered by their position in the text.
 */
class UploadRejectedException(
    val upload: String,
    val errors: List<Diagnostic>
) : IllegalArgumentException(rejectionText(upload, errors)) {

    init {
        require(errors.isNotEmpty()) { "An upload is only rejected for at least one error" }
    }
}

/**
 * Returns the text of the rejection of [upload] for [errors], see [UploadRejectedException].
 */
private fun rejectionText(upload: String, errors: List<Diagnostic>): String {
    val count = if (errors.size == 1) "1 error" else "${errors.size} errors"
    val heading = "Upload of the $upload rejected: $count"
    return (listOf(heading) + errors.map { it.placedLine() }).joinToString("\n")
}

/**
 * Returns this diagnostic as one line `<line>:<column>: <message> (<code>)`: the line and the column of the start
 * of its range, both counted from 1, its message with every line break replaced by a space, and the id of its code.
 */
internal fun Diagnostic.placedLine(): String {
    val oneLineMessage = message.replace(Regex("\\r\\n|\\r|\\n"), " ")
    return "${range.start.line}:${range.start.column}: $oneLineMessage (${code.id})"
}
