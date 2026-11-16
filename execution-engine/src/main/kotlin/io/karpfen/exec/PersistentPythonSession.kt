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

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * A Python process that stays open and runs one piece of code after the other, each in a namespace of its own.
 *
 * The process runs a small loop instead of Python's interactive mode. It reads one line per call, the code encoded in
 * base64, runs the code with `exec` in a fresh dictionary of globals and answers with three lines:
 *
 * 1. `__SENTINEL__OK` when the code left a result, `__SENTINEL__NONE` when it left none, `__SENTINEL__ERROR` when it
 *    failed;
 * 2. the result as text, or the last line of the error, encoded in base64 (empty for `NONE`);
 * 3. everything the code wrote to `sys.stdout` and `sys.stderr` during the call, encoded in base64.
 *
 * The result is the value the code assigns to the global `__karpfen_result__`, which is the last line of the code that
 * [MacroCodeTransformer] builds. The loop turns it into text the way the runtime reads it: a dictionary, a list or a
 * boolean as JSON, every other value with `str`. Because the result travels on a channel of its own, a `print` in the
 * code of a modeler never changes the result. What the code printed comes back as [PythonAnswer.printed], and the
 * caller decides where it goes. Encoding all three lines keeps every character of results and printed text, line breaks
 * of any kind included, out of the line structure of the protocol.
 *
 * Each call starts with fresh globals, so names, functions and changed globals of one call are gone for the next, and
 * the same code text gives the same result whatever ran before it. The interpreter itself stays: a module that one call
 * imports stays loaded in `sys.modules`, and the next import of it is a lookup. Changes that code makes to a module
 * object or to the process, such as an attribute set on `math` or a changed working directory, therefore also stay.
 *
 * After each call the loop empties the namespace. A function that the code defines refers to the namespace as its
 * globals, and the namespace refers to the function, so without this every call would leave a reference cycle with the
 * values of the code in it. Python frees such cycles only in its garbage collection, so emptying the namespace frees
 * the values of a call at its end instead of leaving them to a later collection.
 *
 * The loop keeps the compiled form of up to [MAX_COMPILED] code texts of at most [MAX_COMPILED_LINE] encoded characters
 * and empties this store when it is full. Compiling is the largest part of the work of a short call in Python, and a
 * state machine sends the same text again and again when its values do not change and the result cache is off.
 * Compiling has no effects of its own, so running a kept compiled form is the same as compiling the text again.
 *
 * An exception of any kind in the code, `SystemExit` included, is answered with ERROR and the last line of Python's text
 * for it, such as `ZeroDivisionError: division by zero`, and the process goes on.
 *
 * Before the loop starts, the process moves the protocol to a copy of its stdout and points file descriptor 1 at its
 * stderr. Because the runtime discards that stderr, output that bypasses `sys.stdout`, such as `os.write(1, ...)`, a C
 * library or a child process, is dropped and cannot disturb an answer, and nothing written there can fill a pipe and
 * stop the process.
 *
 * A process that ends during a call, or answers outside the protocol, fails the call with a [PythonExecutionException]
 * and is ended; the next call starts a new one. A call has no time limit: code that does not end holds its call until
 * [abort] kills the process.
 *
 * Thread safety: [execute] and [close] are synchronized, so calls of several threads never interleave on one process. A
 * [PythonSessionPool] gives threads that call at the same time a session each.
 */
class PersistentPythonSession : PythonSession {

    @Volatile
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /**
     * Runs [code] in a fresh namespace of the process, starting the process first when none runs.
     *
     * @throws PythonExecutionException when the process ends during the call or answers outside the protocol.
     * @throws RuntimeException when no Python interpreter can be started.
     */
    @Synchronized
    override fun execute(code: String): PythonAnswer {
        ensureSessionAlive()
        return try {
            exchange(code)
        } catch (e: IOException) {
            destroySession()
            throw PythonExecutionException(ENDED)
        } catch (e: PythonExecutionException) {
            destroySession()
            throw e
        }
    }

    /**
     * Sends [code] and reads the three lines of the answer.
     *
     * @throws PythonExecutionException when the process ends or answers outside the protocol.
     */
    private fun exchange(code: String): PythonAnswer {
        val w = writer ?: throw PythonExecutionException(ENDED)
        val r = reader ?: throw PythonExecutionException(ENDED)

        w.write(Base64.getEncoder().encodeToString(code.toByteArray(Charsets.UTF_8)))
        w.newLine()
        w.flush()

        val sentinel = r.readLine() ?: throw PythonExecutionException(ENDED)
        if (!sentinel.startsWith(SENTINEL)) {
            throw PythonExecutionException("The Python process answered outside the protocol: $sentinel")
        }
        val text = decode(r.readLine() ?: throw PythonExecutionException(ENDED))
        val printed = decode(r.readLine() ?: throw PythonExecutionException(ENDED))
        return when (sentinel.removePrefix(SENTINEL)) {
            "OK" -> PythonAnswer(text, printed)
            "NONE" -> PythonAnswer(null, printed)
            "ERROR" -> PythonAnswer(null, printed, error = text.trim())
            else -> throw PythonExecutionException("The Python process answered outside the protocol: $sentinel")
        }
    }

    /** The text of one base64 line of an answer. */
    private fun decode(line: String): String = try {
        String(Base64.getDecoder().decode(line.trim()), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        throw PythonExecutionException("The Python process answered outside the protocol: $line")
    }

    /** Ends the process and waits up to 3 seconds for it. */
    @Synchronized
    override fun close() {
        destroySession()
    }

    /**
     * Kills the process at once without waiting for a running [execute], which then reads the end of the output and
     * fails. Not synchronized, so it reaches a process whose answer another thread waits for.
     */
    override fun abort() {
        process?.destroyForcibly()
    }

    /** True while the process runs. */
    @Synchronized
    fun isAlive(): Boolean = process?.isAlive == true

    /** The process id of the running process, or null when none runs. */
    @Synchronized
    fun pid(): Long? = process?.takeIf { it.isAlive }?.pid()

    private fun ensureSessionAlive() {
        if (process?.isAlive == true) return
        startSession()
    }

    /** Starts the process with the loop of [LOOP], unbuffered and with UTF-8 for its streams. */
    private fun startSession() {
        destroySession()
        val processBuilder = ProcessBuilder(PythonCommand.find(), "-u", "-c", LOOP)
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD)
        processBuilder.environment()["PYTHONIOENCODING"] = "utf-8"
        processBuilder.environment()["PYTHONDONTWRITEBYTECODE"] = "1"

        val proc = processBuilder.start()
        process = proc
        writer = proc.outputStream.bufferedWriter(Charsets.UTF_8)
        reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
    }

    /** Closes the streams and ends the process, by force when it has not ended after 3 seconds. */
    private fun destroySession() {
        try { writer?.close() } catch (_: IOException) {}
        try { reader?.close() } catch (_: IOException) {}
        process?.let { proc ->
            proc.destroy()
            try {
                if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
            } catch (_: InterruptedException) {
                proc.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
        process = null
        writer = null
        reader = null
    }

    private companion object {
        const val SENTINEL = "__SENTINEL__"
        const val ENDED = "The Python process ended during the call"

        /** The most compiled code texts the loop keeps; see the class description. */
        const val MAX_COMPILED = 256

        /** The longest encoded code text, in characters, whose compiled form the loop keeps. */
        const val MAX_COMPILED_LINE = 16_384

        /** The loop the process runs; see the class description for its protocol. */
        val LOOP = """
import sys, os, base64, io, json, traceback, builtins

def __karpfen_encode__(text):
    return base64.b64encode(text.encode('utf-8', 'replace')).decode('ascii')

def __karpfen_text__(value):
    if isinstance(value, (dict, list, bool)):
        return json.dumps(value)
    return str(value)

__karpfen_compiled__ = {}
__karpfen_out__ = os.fdopen(os.dup(1), "w", encoding="ascii")
os.dup2(2, 1)
while True:
    line = sys.stdin.readline()
    if not line:
        break
    line = line.strip()
    if not line:
        continue
    printed = io.StringIO()
    status, text = 'NONE', ''
    namespace = {}
    try:
        compiled = __karpfen_compiled__.get(line)
        if compiled is None:
            compiled = compile(base64.b64decode(line).decode('utf-8'), '<string>', 'exec')
            if len(line) <= $MAX_COMPILED_LINE:
                if len(__karpfen_compiled__) >= $MAX_COMPILED:
                    __karpfen_compiled__.clear()
                __karpfen_compiled__[line] = compiled
        namespace = {'__name__': '__main__', '__builtins__': builtins}
        sys.stdout = sys.stderr = printed
        exec(compiled, namespace)
        result = namespace.get('__karpfen_result__')
        if result is not None:
            status, text = 'OK', __karpfen_text__(result)
    except BaseException as e:
        lines = [l.strip() for l in traceback.format_exception_only(type(e), e) if l.strip()]
        status, text = 'ERROR', lines[-1] if lines else type(e).__name__
    finally:
        sys.stdout, sys.stderr = __karpfen_out__, sys.__stderr__
        namespace.clear()
    __karpfen_out__.write('__SENTINEL__' + status + '\n' + __karpfen_encode__(text) + '\n'
                          + __karpfen_encode__(printed.getvalue()) + '\n')
    __karpfen_out__.flush()
""".trimIndent()
    }
}
