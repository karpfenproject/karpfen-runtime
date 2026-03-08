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

import java.io.*
import java.util.concurrent.TimeUnit

/**
 * Maintains a persistent Python subprocess for executing macro code.
 *
 * Instead of spawning a new Python process for each macro execution, this class keeps
 * a single Python process alive and sends code snippets to its stdin. Results are read
 * from stdout using a unique sentinel-based protocol to delimit output.
 *
 * The subprocess runs a custom REPL loop (not Python's interactive mode) that reads
 * base64-encoded code snippets from stdin, executes them via exec(), and prints a
 * sentinel line after each execution. This avoids all interactive-mode issues with
 * compound statements, blank lines, and prompt handling.
 *
 * Benefits:
 * - Eliminates Python interpreter startup overhead (~100-300ms) on each invocation.
 * - Import statements (e.g., `import math`, `import numpy`) are cached by the interpreter
 *   across invocations.
 * - No temp file I/O.
 *
 * Thread safety: All methods that interact with the subprocess are `synchronized`.
 * The engine currently executes ticks sequentially, so a single session suffices.
 */
class PersistentPythonSession {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var pythonCommand: String? = null

    /**
     * Executes the given Python code in the persistent session and returns stdout output.
     *
     * The code is base64-encoded and sent as a single line to the subprocess's stdin.
     * The REPL loop decodes and executes it via exec(), then prints a sentinel.
     * Everything printed before the sentinel is returned as the result.
     *
     * @param code The Python code to execute.
     * @return The stdout output (trimmed), or null if there was no output.
     * @throws RuntimeException if the Python execution fails or the session cannot be started.
     */
    @Synchronized
    fun execute(code: String): String? {
        ensureSessionAlive()

        try {
            val w = writer ?: throw RuntimeException("Python session writer is not available")
            val r = reader ?: throw RuntimeException("Python session reader is not available")

            // Send base64-encoded code as a single line
            val encoded = java.util.Base64.getEncoder().encodeToString(code.toByteArray(Charsets.UTF_8))
            w.write(encoded)
            w.newLine()
            w.flush()

            // Read the sentinel line first (format: __SENTINEL__<status>)
            // Then read the number of output lines, then the output lines themselves.
            val sentinelLine = r.readLine() ?: throw RuntimeException(
                "Python session terminated unexpectedly while executing code"
            )

            if (!sentinelLine.startsWith("__SENTINEL__")) {
                throw RuntimeException("Protocol error: expected sentinel, got: $sentinelLine")
            }

            val status = sentinelLine.removePrefix("__SENTINEL__")
            val lineCountStr = r.readLine() ?: throw RuntimeException(
                "Python session terminated unexpectedly while reading line count"
            )
            val lineCount = lineCountStr.toIntOrNull() ?: throw RuntimeException(
                "Protocol error: expected line count, got: $lineCountStr"
            )

            val output = StringBuilder()
            repeat(lineCount) { i ->
                val line = r.readLine() ?: throw RuntimeException(
                    "Python session terminated unexpectedly while reading output line ${i+1}/$lineCount"
                )
                if (output.isNotEmpty()) output.append("\n")
                output.append(line)
            }

            if (status == "ERROR") {
                val errorMsg = output.toString().trim()
                System.err.println("[PersistentPythonSession] Python error: $errorMsg")
                return null
            }

            val result = output.toString().trim()
            return if (result.isEmpty()) null else result

        } catch (e: IOException) {
            // The process likely died — clean up and let the next call restart it
            destroySession()
            throw RuntimeException("Python session I/O error: ${e.message}", e)
        }
    }

    /**
     * Closes the persistent Python session, terminating the subprocess.
     */
    @Synchronized
    fun close() {
        destroySession()
    }

    /**
     * Returns true if the session is currently active.
     */
    @Synchronized
    fun isAlive(): Boolean {
        return process?.isAlive == true
    }

    /**
     * Ensures the Python session is alive, starting it if necessary.
     */
    private fun ensureSessionAlive() {
        if (process?.isAlive == true) return
        startSession()
    }

    /**
     * Starts a new Python subprocess running a custom REPL loop.
     *
     * The REPL loop reads base64-encoded code from stdin (one line per request),
     * decodes and executes it, captures stdout, and prints a structured response:
     *   __SENTINEL__OK (or __SENTINEL__ERROR)
     *   <number of output lines>
     *   <output line 1>
     *   <output line 2>
     *   ...
     */
    private fun startSession() {
        destroySession() // Clean up any existing dead session

        val cmd = findPythonCommand()
        pythonCommand = cmd

        // The REPL script: reads base64 lines from stdin, exec()s them, sends structured output
        val replScript = """
import sys, base64, io

while True:
    try:
        line = sys.stdin.readline()
    except EOFError:
        break
    if not line:
        break
    line = line.strip()
    if not line:
        continue
    captured = io.StringIO()
    old_stdout = sys.stdout
    try:
        code = base64.b64decode(line).decode('utf-8')
        sys.stdout = captured
        exec(code)
        sys.stdout = old_stdout
        output = captured.getvalue()
        lines = output.split('\n')
        if lines and lines[-1] == '':
            lines = lines[:-1]
        old_stdout.write('__SENTINEL__OK\n')
        old_stdout.write(str(len(lines)) + '\n')
        for l in lines:
            old_stdout.write(l + '\n')
        old_stdout.flush()
    except Exception as e:
        sys.stdout = old_stdout
        err_msg = str(e)
        old_stdout.write('__SENTINEL__ERROR\n')
        old_stdout.write('1\n')
        old_stdout.write(err_msg + '\n')
        old_stdout.flush()
""".trimIndent()

        // -u: unbuffered stdout/stderr
        val processBuilder = ProcessBuilder(cmd, "-u", "-c", replScript)
        processBuilder.redirectErrorStream(false)
        processBuilder.environment()["PYTHONIOENCODING"] = "utf-8"
        processBuilder.environment()["PYTHONDONTWRITEBYTECODE"] = "1"

        val proc = processBuilder.start()
        process = proc
        writer = proc.outputStream.bufferedWriter()
        reader = proc.inputStream.bufferedReader()
    }

    /**
     * Destroys the current session, closing all streams and terminating the process.
     */
    private fun destroySession() {
        try { writer?.close() } catch (_: IOException) {}
        try { reader?.close() } catch (_: IOException) {}
        process?.let { proc ->
            proc.destroy()
            try {
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                proc.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
        process = null
        writer = null
        reader = null
    }

    /**
     * Finds the Python command available on the system.
     * Uses the cached value if available, otherwise probes python3/python/py.
     */
    private fun findPythonCommand(): String {
        pythonCommand?.let { return it }

        for (cmd in listOf("python3", "python", "py")) {
            try {
                val process = ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor(5, TimeUnit.SECONDS)
                if (process.exitValue() == 0) {
                    pythonCommand = cmd
                    return cmd
                }
            } catch (_: Exception) { }
        }

        throw RuntimeException(
            "No Python interpreter found. Please ensure 'python3', 'python' or 'py' is available on the system PATH."
        )
    }
}
