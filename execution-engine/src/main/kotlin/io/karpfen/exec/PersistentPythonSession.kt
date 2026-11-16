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
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Maintains a persistent Python subprocess for executing macro code.
 *
 * Instead of spawning a new Python process for each macro execution, this class keeps
 * a single Python process alive and sends code snippets to its stdin. Results are read
 * from stdout in a fixed form of two lines per call.
 *
 * The subprocess runs a custom REPL loop (not Python's interactive mode) that reads
 * base64-encoded code snippets from stdin, executes them via exec(), and answers each with
 * two lines: a sentinel with the status, `__SENTINEL__OK` or `__SENTINEL__ERROR`, and the
 * base64-encoded UTF-8 text of what the code printed, or of the last line of the error.
 * Encoding the answer keeps every character the code prints, also line breaks of any kind,
 * out of the line structure of the protocol.
 *
 * While the code runs, the loop captures `sys.stdout` and `sys.stderr`. What the code prints
 * to stdout is its result; what it writes to stderr is dropped when it succeeds. An exception
 * of any kind in the code, `SystemExit` included, is answered with ERROR and the last line of
 * Python's text for it, such as `ZeroDivisionError: division by zero`, and the process goes on.
 * The process's own stderr is discarded, so nothing written to it can fill a pipe and stop the
 * process.
 *
 * Benefits:
 * - Eliminates Python interpreter startup overhead (~100-300ms) on each invocation.
 * - Import statements (e.g., `import math`, `import numpy`) are cached by the interpreter
 *   across invocations.
 * - No temp file I/O.
 *
 * Failures: an error in the code fails the call with a [PythonExecutionException] and keeps the
 * process. A process that ends during a call, or answers outside the protocol, fails the call
 * with a [PythonExecutionException] too and is ended; the next call starts a new one.
 * A call has no time limit: code that does not end holds its call until [abort] kills the process.
 *
 * Thread safety: All methods that interact with the subprocess are `synchronized`, so calls of
 * several threads never interleave on one process. A [PythonSessionPool] gives threads that call
 * at the same time a session each.
 */
class PersistentPythonSession : PythonSession {

    @Volatile
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var pythonCommand: String? = null

    /**
     * Executes the given Python code in the persistent session and returns stdout output.
     *
     * The code is base64-encoded and sent as a single line to the subprocess's stdin.
     * The REPL loop decodes and executes it via exec(), then answers with its status and output.
     *
     * @param code The Python code to execute.
     * @return The stdout output as [PythonProcess.printed] gives it, or null if there was no output.
     * @throws PythonExecutionException if the code fails in Python or the process ends during the call.
     * @throws RuntimeException if the session cannot be started.
     */
    @Synchronized
    override fun execute(code: String): String? {
        ensureSessionAlive()

        val (status, text) = try {
            exchange(code)
        } catch (e: IOException) {
            destroySession()
            throw PythonExecutionException("The Python process ended during the call")
        } catch (e: PythonExecutionException) {
            destroySession()
            throw e
        }

        if (status == "ERROR") throw PythonExecutionException.raised(text.trim())
        return PythonProcess.printed(text)
    }

    /**
     * Sends [code] and reads the answer: the status after the sentinel and the decoded text.
     *
     * @throws PythonExecutionException when the process ends or answers outside the protocol.
     */
    private fun exchange(code: String): Pair<String, String> {
        val w = writer ?: throw PythonExecutionException("The Python process ended during the call")
        val r = reader ?: throw PythonExecutionException("The Python process ended during the call")

        w.write(Base64.getEncoder().encodeToString(code.toByteArray(Charsets.UTF_8)))
        w.newLine()
        w.flush()

        val sentinelLine = r.readLine() ?: throw PythonExecutionException("The Python process ended during the call")
        if (!sentinelLine.startsWith("__SENTINEL__")) {
            throw PythonExecutionException("The Python process answered outside the protocol: $sentinelLine")
        }
        val encoded = r.readLine() ?: throw PythonExecutionException("The Python process ended during the call")
        val text = try {
            String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw PythonExecutionException("The Python process answered outside the protocol: $encoded")
        }
        return sentinelLine.removePrefix("__SENTINEL__") to text
    }

    /**
     * Closes the persistent Python session, terminating the subprocess.
     */
    @Synchronized
    override fun close() {
        destroySession()
    }

    /**
     * Kills the subprocess at once without waiting for a running [execute], which then reads the end of the output and
     * fails. Not synchronized, so it reaches a process whose answer another thread waits for.
     */
    override fun abort() {
        process?.destroyForcibly()
    }

    /**
     * Returns true if the session is currently active.
     */
    @Synchronized
    fun isAlive(): Boolean {
        return process?.isAlive == true
    }

    /** The process id of the running subprocess, or null when none runs. */
    @Synchronized
    fun pid(): Long? = process?.takeIf { it.isAlive }?.pid()

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
     * decodes and executes it with stdout and stderr captured, and answers:
     *   __SENTINEL__OK (or __SENTINEL__ERROR)
     *   <base64 of the captured stdout, or of the last line of the error>
     */
    private fun startSession() {
        destroySession() // Clean up any existing dead session

        val cmd = findPythonCommand()
        pythonCommand = cmd

        val replScript = """
import sys, base64, io, traceback

def __karpfen_answer__(out, status, text):
    out.write('__SENTINEL__' + status + '\n')
    out.write(base64.b64encode(text.encode('utf-8', 'replace')).decode('ascii') + '\n')
    out.flush()

while True:
    line = sys.stdin.readline()
    if not line:
        break
    line = line.strip()
    if not line:
        continue
    captured = io.StringIO()
    old_stdout, old_stderr = sys.stdout, sys.stderr
    try:
        code = base64.b64decode(line).decode('utf-8')
        sys.stdout, sys.stderr = captured, io.StringIO()
        exec(code)
        sys.stdout, sys.stderr = old_stdout, old_stderr
        __karpfen_answer__(old_stdout, 'OK', captured.getvalue())
    except BaseException as e:
        sys.stdout, sys.stderr = old_stdout, old_stderr
        lines = [l.strip() for l in traceback.format_exception_only(type(e), e) if l.strip()]
        __karpfen_answer__(old_stdout, 'ERROR', lines[-1] if lines else type(e).__name__)
""".trimIndent()

        // -u: unbuffered stdout/stderr
        val processBuilder = ProcessBuilder(cmd, "-u", "-c", replScript)
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD)
        processBuilder.environment()["PYTHONIOENCODING"] = "utf-8"
        processBuilder.environment()["PYTHONDONTWRITEBYTECODE"] = "1"

        val proc = processBuilder.start()
        process = proc
        writer = proc.outputStream.bufferedWriter(Charsets.UTF_8)
        reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
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
