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
 * Code of an `EVAL` or a macro failed in Python: it raised an exception, did not compile, or its process ended during
 * the call. The message is what a modeler reads in the trace log and the console, so it is one line:
 * - `Python error: <type>: <message>` for an exception, the last line Python writes for it, such as
 *   `Python error: ZeroDivisionError: division by zero` or `Python error: SyntaxError: invalid syntax`;
 * - `The Python process ended during the call` for a persistent process that is gone.
 *
 * Action processing reports it as an error of the action, a condition takes it as false.
 */
class PythonExecutionException(message: String) : RuntimeException(message) {

    companion object {

        /** The prefix of the message of an exception raised in Python. */
        const val ERROR_PREFIX = "Python error: "

        /** The exception for an exception in Python whose last line of text is [lastLine]. */
        fun raised(lastLine: String) = PythonExecutionException(ERROR_PREFIX + lastLine)
    }
}
