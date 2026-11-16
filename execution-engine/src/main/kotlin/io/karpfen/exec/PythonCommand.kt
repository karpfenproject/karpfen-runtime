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
 * Finds the command that starts Python: the first of `python3`, `python` and `py` that runs with `--version`. The
 * search runs once per JVM, since starting a process for it on every call would cost as much as the call.
 */
object PythonCommand {

    private val command: String by lazy { search() }

    /**
     * The command that starts Python.
     *
     * @throws RuntimeException when none of the three commands runs.
     */
    fun find(): String = command

    private fun search(): String {
        for (cmd in listOf("python3", "python", "py")) {
            try {
                val process = ProcessBuilder(cmd, "--version").redirectErrorStream(true).start()
                process.inputStream.readAllBytes()
                if (process.waitFor() == 0) return cmd
            } catch (_: Exception) {
            }
        }
        throw RuntimeException(
            "No Python interpreter found. Please ensure 'python3', 'python' or 'py' is available on the system PATH."
        )
    }
}
