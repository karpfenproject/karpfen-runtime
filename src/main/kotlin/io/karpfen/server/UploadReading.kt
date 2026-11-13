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

import io.karpfen.dsl.diagnostics.Severity
import io.karpfen.dsl.reader.ParseResult

/**
 * Decides whether an uploaded text is accepted, from what a reader of the karpfen DSL tools read from it.
 *
 * The upload routes read every text with `KmetaReader`, `KmodelReader` or `KstatesReader` and hand the
 * [ParseResult] here, the one place that turns a read into an accepted model or a rejection.
 */
object UploadReading {

    /**
     * Returns the model of [result], read from the [upload] of the environment [envKey], if the read found no error.
     *
     * Warnings do not reject an upload; each is logged on the console with its place in the text. A read with at
     * least one error is refused with an [UploadRejectedException] that lists every error with its place.
     *
     * @param upload What was uploaded, in words that complete "Upload of the ...", such as `metamodel`.
     * @throws UploadRejectedException if the read found an error.
     * @throws RuntimeException if the reader yielded neither a model nor an error, which its contract rules out; the
     * routes answer it with `500 Internal Server Error`.
     */
    fun <M : Any> acceptOrReject(envKey: String, upload: String, result: ParseResult<M>): M {
        if (result.hasErrors) {
            throw UploadRejectedException(upload, result.errors)
        }
        result.diagnostics.filter { it.severity == Severity.WARNING }.forEach { warning ->
            println("[UploadReading] Warning in the $upload of environment $envKey: ${warning.placedLine()}")
        }
        return result.model
            ?: throw RuntimeException("The reader of the $upload yielded neither a model nor an error")
    }
}
