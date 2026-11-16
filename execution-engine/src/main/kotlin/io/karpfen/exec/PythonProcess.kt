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

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Runs one Python process to its end and returns what it printed: the way of `EVAL` blocks and macros that start a new
 * process for every call.
 *
 * The process writes to two pipes, and a pipe that nobody reads fills up and stops the process. So a thread of its own
 * reads stderr while the caller writes the code to stdin and reads stdout to the end; neither stream can block the
 * other, however much the code prints to either. Of stderr only the last [STDERR_TAIL] characters are kept, enough for
 * the message of an exception, which Python writes last.
 *
 * There is no time limit: a process that does not end holds its caller until it ends or is killed.
 */
object PythonProcess {

    /** The number of characters at the end of stderr that are kept for the message of a failure. */
    const val STDERR_TAIL = 16 * 1024

    /**
     * Starts [command], writes [stdin] to it when it is not null and closes its stdin, and waits for its end.
     *
     * @return what it printed to stdout without the line break that ends the last `print`, or null when it printed
     *   nothing.
     * @throws PythonExecutionException when it ends with an exit code other than 0.
     */
    fun run(command: List<String>, stdin: String?): String? {
        val processBuilder = ProcessBuilder(command)
        processBuilder.environment()["PYTHONIOENCODING"] = "utf-8"
        val process = processBuilder.start()

        var stderr = ""
        val stderrReader = thread(name = "python-stderr", isDaemon = true) { stderr = readTail(process.errorStream) }
        try {
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer -> stdin?.let(writer::write) }
            } catch (_: IOException) {
                // The process ended before it read all of stdin; its exit code and stderr tell why.
            }
            val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val exitCode = process.waitFor()
            stderrReader.join()
            if (exitCode != 0) throw PythonExecutionException.ended(exitCode, stderr)
            return printed(stdout, System.lineSeparator())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * The result in the text [stdout] of a call: null when the code printed nothing, otherwise the text without the
     * one [lineBreak] that `print` writes at its end, so that a string keeps every other white space and line break.
     */
    fun printed(stdout: String, lineBreak: String = "\n"): String? =
        if (stdout.isEmpty()) null else stdout.removeSuffix(lineBreak)

    /** Reads [stream] to its end and returns its last [STDERR_TAIL] characters. */
    private fun readTail(stream: InputStream): String {
        val tail = StringBuilder()
        val buffer = CharArray(8 * 1024)
        InputStreamReader(stream, Charsets.UTF_8).use { reader ->
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                tail.append(buffer, 0, read)
                if (tail.length > 2 * STDERR_TAIL) tail.delete(0, tail.length - STDERR_TAIL)
            }
        }
        return if (tail.length > STDERR_TAIL) tail.substring(tail.length - STDERR_TAIL) else tail.toString()
    }
}
