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
 *    failed, `__SENTINEL__REFUSED` when the check of the process calls refused it;
 * 2. the result as text, the last line of the error or the refusal, encoded in base64 (empty for `NONE`);
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
 * Running a kept compiled form is the same as compiling the text again, except that a warning of the compiler, such as
 * a `SyntaxWarning`, comes only with the call that compiled the text.
 *
 * Unless the process was started with `allowProcessCalls` (see [startPython]), the loop checks a code text that it has
 * not kept compiled with [PROCESS_CALL_CHECK] before it compiles it. A refused text is neither compiled nor kept, and
 * nothing of it runs. The answer is REFUSED with the reason, and the process goes on. Since only a text that passed is
 * kept, a kept text is not checked again.
 *
 * An exception of any kind in the code, `SystemExit` included, is answered with ERROR and the last line of Python's text
 * for it, such as `ZeroDivisionError: division by zero`, and the process goes on.
 *
 * Before the loop starts, the process sets up its streams so that only the loop can reach the protocol:
 * - The loop reads its requests from a copy of file descriptor 0 and writes its answers to a copy of file descriptor 1.
 *   Neither copy is inherited by child processes, and the loop holds both in local variables of its function, so no
 *   name of the code leads to them.
 * - File descriptor 0 then reads from the null device. Code that calls `input()` or reads `sys.stdin` gets the end of
 *   the input at once, `input()` raises `EOFError`, and a child process that the code starts cannot read a request.
 * - File descriptor 1 then points at stderr. Because the runtime discards that stderr, output that bypasses
 *   `sys.stdout`, such as `os.write(1, ...)`, a C library or a child process, is dropped and cannot disturb an answer,
 *   and nothing written there can fill a pipe and stop the process.
 * - `sys.stdout` and `sys.stderr` are two stream objects that stay the same for the life of the process and ignore
 *   `close()`. What is written to them goes to the buffer of the call that runs at that moment and is discarded when no
 *   call runs. Because the objects never change, a `logging` handler that took `sys.stderr` in one call writes to the
 *   call that logs in every later call. A thread or a timer that a call started and that prints after the call returned
 *   cannot write into the protocol either.
 *
 * The loop compiles the code after it has set the buffer of the call. A warning of the compiler is therefore part of
 * what the call printed.
 *
 * A process that ends during a call, or answers outside the protocol, fails the call with a [PythonExecutionException]
 * and is ended; the next call starts a new one. A call has no time limit: code that does not end holds its call until
 * [abort] kills the process.
 *
 * Thread safety: [execute] and [close] are synchronized, so calls of several threads never interleave on one process. A
 * [PythonSessionPool] gives threads that call at the same time a session each.
 *
 * @property start starts the process with the loop; by default Python with [LOOP], unbuffered and with UTF-8 for its
 *   streams, which refuses process calls. Tests of the handling of the process on the Kotlin side pass a process that answers from fixed text.
 */
class PersistentPythonSession(private val start: () -> Process = { startPython() }) : PythonSession {

    @Volatile
    private var process: Process? = null

    /** True once [abort] ran; the session then starts no process any more. */
    @Volatile
    private var aborted = false
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /**
     * Runs [code] in a fresh namespace of the process, starting the process first when none runs.
     *
     * A call that does not end with a whole answer ends the process, whatever went wrong: the process ending, an answer
     * outside the protocol, or an [Error] such as an [OutOfMemoryError] while the answer is read. Lines of the answer
     * may still wait in the pipe then, and a later call on the same process would take them as its own answer.
     *
     * @throws PythonExecutionException when the process ends during the call, answers outside the protocol, or the
     *   session was aborted.
     * @throws RuntimeException when no Python interpreter can be started.
     */
    @Synchronized
    override fun execute(code: String): PythonAnswer {
        if (aborted) throw PythonExecutionException(ABORTED)
        ensureSessionAlive()
        var answered = false
        try {
            return exchange(code).also { answered = true }
        } catch (e: IOException) {
            throw PythonExecutionException(ENDED)
        } finally {
            if (!answered) destroySession()
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
            "REFUSED" -> PythonAnswer(null, printed, refusal = text.trim())
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
     * fails, and keeps the session from starting a process again. Not synchronized, so it reaches a process whose answer
     * another thread waits for. A call that is about to start the process when the abort comes finds the mark after the
     * start and kills its process itself, since the abort sets the mark before it looks at the process and the start
     * sets the process before it looks at the mark.
     */
    override fun abort() {
        aborted = true
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

    /** Starts the process through [start] and opens its streams. */
    private fun startSession() {
        destroySession()
        val proc = start()
        process = proc
        if (aborted) {
            proc.destroyForcibly()
            process = null
            throw PythonExecutionException(ABORTED)
        }
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

    companion object {

        /**
         * Starts Python with the loop of [LOOP], unbuffered and with UTF-8 for its streams.
         *
         * @param allowProcessCalls true to run every code text without the check of [PROCESS_CALL_CHECK]; false, the
         *   default, to refuse code that uses a way to start a process.
         */
        fun startPython(allowProcessCalls: Boolean = false): Process {
            val mode = if (allowProcessCalls) ALLOW_PROCESS_CALLS else REFUSE_PROCESS_CALLS
            val processBuilder = ProcessBuilder(PythonCommand.find(), "-u", "-c", LOOP, mode)
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD)
            processBuilder.environment()["PYTHONIOENCODING"] = "utf-8"
            processBuilder.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
            return processBuilder.start()
        }

        private const val SENTINEL = "__SENTINEL__"
        private const val ENDED = "The Python process ended during the call"
        private const val ABORTED = "The Python sessions of this environment were ended"

        /** The most compiled code texts the loop keeps; see the class description. */
        private const val MAX_COMPILED = 256

        /** The longest encoded code text, in characters, whose compiled form the loop keeps. */
        private const val MAX_COMPILED_LINE = 16_384

        /** The argument of the loop that lets every code text run without the check of [PROCESS_CALL_CHECK]. */
        private const val ALLOW_PROCESS_CALLS = "allow-process-calls"

        /** The argument of the loop that checks every new code text with [PROCESS_CALL_CHECK]; any other does too. */
        private const val REFUSE_PROCESS_CALLS = "refuse-process-calls"

        /**
         * The Python of the check that refuses code which can start a process, part of [LOOP].
         *
         * `process_call_check(ast, warnings, compile)` returns a function that takes a code text and gives the text of
         * the refusal, or None when the code may run. It parses the text into its syntax tree and walks the tree, so
         * the text inside string literals, where the runtime writes the values of the model, never counts, while the
         * expressions inside an f-string do. A text that does not parse gives None, and compiling it then raises the
         * usual `SyntaxError`. The warnings of the parser are ignored, because compiling the text gives them again.
         *
         * Refused are:
         * - an `import` or `from ... import` of a module of `forbidden_modules` or of a submodule of one, under any
         *   name;
         * - a process function of `os`, `posix` or `nt` (`system`, `popen`, `startfile` and every name that begins with
         *   `exec`, `spawn`, `fork` or `posix_spawn`), reached as an attribute of the module, of another name bound to
         *   it by `import ... as` or by an assignment, of an attribute named like the module (`os.path.os`), through
         *   `getattr` with the name as a string literal, or imported by name or with `*`;
         * - an attribute of `forbidden_attributes` of any object, also through `getattr` with a string literal, and an
         *   import of such a name;
         * - every use of a name of `forbidden_names`, and a subscript with the string literal `'__builtins__'`.
         *
         * The refusal names the first use in the code, by line and column, and its line. Since the runtime wraps the
         * code of an `EVAL` or a directly called macro in the function `__karpfen_main__` and every macro the code
         * calls in a function named after the macro, a line inside such a function is counted from the line after its
         * `def`, which is the first line of the code of the `EVAL` or the macro. A line in a macro also names the
         * macro.
         *
         * The function holds every name it needs, the classes of `ast` included, in its own variables, so code that
         * changes the module `ast` or `warnings` in one call does not change the check of a later call.
         */
        private val PROCESS_CALL_CHECK = """
def process_call_check(ast, warnings, compile):
    forbidden_modules = (
        'subprocess', '_posixsubprocess', '_winapi', 'pty', 'multiprocessing', 'pip', 'ensurepip', 'importlib',
        'runpy', 'ctypes', '_ctypes', 'builtins', 'code', 'codeop', 'distutils',
    )
    os_modules = ('os', 'posix', 'nt')
    os_functions = ('system', 'popen', 'startfile')
    os_function_prefixes = ('exec', 'spawn', 'fork', 'posix_spawn')
    forbidden_attributes = (
        'subprocess', '_posixsubprocess', 'pty', 'multiprocessing', 'create_subprocess_exec',
        'create_subprocess_shell', 'subprocess_exec', 'subprocess_shell', '__builtins__', '__globals__',
        '__subclasses__',
    )
    forbidden_names = ('eval', 'exec', 'compile', '__import__', '__builtins__')
    only_tree = ast.PyCF_ONLY_AST
    walk = ast.walk
    Import, ImportFrom, Name, Attribute = ast.Import, ast.ImportFrom, ast.Name, ast.Attribute
    Call, Subscript, Constant = ast.Call, ast.Subscript, ast.Constant
    Assign, NamedExpr = ast.Assign, ast.NamedExpr
    FunctionDef, AsyncFunctionDef = ast.FunctionDef, ast.AsyncFunctionDef
    catch_warnings, simplefilter = warnings.catch_warnings, warnings.simplefilter

    def forbidden_module(name):
        for module in forbidden_modules:
            if name == module or name.startswith(module + '.'):
                return module
        return None

    def is_os_function(name):
        return name in os_functions or name.startswith(os_function_prefixes)

    def os_module(node, aliases):
        if isinstance(node, Name):
            return aliases.get(node.id)
        if isinstance(node, Attribute) and node.attr in os_modules:
            return node.attr
        return None

    def os_aliases(tree):
        aliases = {module: module for module in os_modules}
        bindings = []
        for node in walk(tree):
            if isinstance(node, Import):
                for alias in node.names:
                    if alias.name in os_modules and alias.asname:
                        aliases[alias.asname] = alias.name
            elif isinstance(node, Assign):
                bindings.extend((target, node.value) for target in node.targets)
            elif isinstance(node, NamedExpr):
                bindings.append((node.target, node.value))
        grown = True
        while grown:
            grown = False
            for target, value in bindings:
                module = os_module(value, aliases)
                if module is not None and isinstance(target, Name) and target.id not in aliases:
                    aliases[target.id] = module
                    grown = True
        return aliases

    def attribute_use(owner, name, aliases):
        module = os_module(owner, aliases)
        if module is not None and is_os_function(name):
            return module + '.' + name
        if name in forbidden_attributes:
            return name
        return None

    def import_use(node):
        for alias in node.names:
            module = forbidden_module(alias.name)
            if module is not None:
                return module
        return None

    def import_from_use(node):
        module = forbidden_module(node.module or '')
        if module is not None:
            return module
        for alias in node.names:
            if node.module in os_modules and (alias.name == '*' or is_os_function(alias.name)):
                return node.module + '.' + alias.name
            if alias.name in forbidden_attributes or alias.name in forbidden_names:
                return alias.name
        return None

    def getattr_use(node, aliases):
        if isinstance(node.func, Name) and node.func.id == 'getattr' and len(node.args) >= 2:
            name = node.args[1]
            if isinstance(name, Constant) and isinstance(name.value, str):
                return attribute_use(node.args[0], name.value, aliases)
        return None

    def node_use(node, aliases):
        if isinstance(node, Import):
            return import_use(node)
        if isinstance(node, ImportFrom):
            return import_from_use(node)
        if isinstance(node, Name):
            return node.id if node.id in forbidden_names else None
        if isinstance(node, Attribute):
            return attribute_use(node.value, node.attr, aliases)
        if isinstance(node, Call):
            return getattr_use(node, aliases)
        if isinstance(node, Subscript):
            key = node.slice
            if isinstance(key, Constant) and key.value == '__builtins__':
                return '__builtins__'
        return None

    def place(statement, line):
        if isinstance(statement, (FunctionDef, AsyncFunctionDef)):
            line = line - statement.lineno
            if statement.name != '__karpfen_main__':
                return "line %d of macro '%s'" % (line, statement.name)
        return 'line %d' % line

    def refusal_of(source):
        try:
            with catch_warnings():
                simplefilter('ignore')
                tree = compile(source, '<string>', 'exec', only_tree)
        except (SyntaxError, ValueError):
            return None
        aliases = os_aliases(tree)
        for statement in tree.body:
            found = []
            for node in walk(statement):
                use = node_use(node, aliases)
                if use is not None:
                    found.append((node.lineno, node.col_offset, use))
            if found:
                line, column, use = min(found)
                return (
                    "Code may not use '%s' (%s). Process calls are switched off; "
                    "engine.allowProcessCalls = true in application.conf allows them." % (use, place(statement, line))
                )
        return None

    return refusal_of
""".trimIndent()

        /** The loop the process runs; see the class description for its protocol. */
        private val LOOP = """
import sys, os, base64, io, json, traceback, builtins, ast, warnings

def __karpfen_loop__():
    def encode(text):
        return base64.b64encode(text.encode('utf-8', 'replace')).decode('ascii')

    def as_text(value):
        if isinstance(value, (dict, list, bool)):
            return json.dumps(value)
        return str(value)

    class CallOutput(io.TextIOBase):
        call = None
        encoding = 'utf-8'

        def writable(self):
            return True

        def close(self):
            pass

        def write(self, text):
            if not isinstance(text, str):
                raise TypeError('write() argument must be str, not ' + type(text).__name__)
            buffer = CallOutput.call
            if buffer is not None:
                buffer.write(text)
            return len(text)

    requests = os.fdopen(os.dup(0), 'r', encoding='ascii')
    answers = os.fdopen(os.dup(1), 'w', encoding='ascii')
    empty = os.open(os.devnull, os.O_RDONLY)
    os.dup2(empty, 0)
    os.close(empty)
    os.dup2(2, 1)
${PROCESS_CALL_CHECK.prependIndent("    ")}

    def no_refusal(source):
        return None

    if sys.argv[1:] == ['$ALLOW_PROCESS_CALLS']:
        refusal_of = no_refusal
    else:
        refusal_of = process_call_check(ast, warnings, compile)

    stdout, stderr = CallOutput(), CallOutput()
    sys.stdout, sys.stderr = stdout, stderr
    compiled_texts = {}
    while True:
        line = requests.readline()
        if not line:
            break
        line = line.strip()
        if not line:
            continue
        printed = io.StringIO()
        status, text = 'NONE', ''
        namespace = {}
        CallOutput.call = printed
        try:
            compiled = compiled_texts.get(line)
            refusal = None
            if compiled is None:
                source = base64.b64decode(line).decode('utf-8')
                refusal = refusal_of(source)
                if refusal is None:
                    compiled = compile(source, '<string>', 'exec')
                    if len(line) <= $MAX_COMPILED_LINE:
                        if len(compiled_texts) >= $MAX_COMPILED:
                            compiled_texts.clear()
                        compiled_texts[line] = compiled
            if refusal is not None:
                status, text = 'REFUSED', refusal
            else:
                namespace = {'__name__': '__main__', '__builtins__': builtins}
                exec(compiled, namespace)
                result = namespace.get('__karpfen_result__')
                if result is not None:
                    status, text = 'OK', as_text(result)
        except BaseException as e:
            lines = [l.strip() for l in traceback.format_exception_only(type(e), e) if l.strip()]
            status, text = 'ERROR', lines[-1] if lines else type(e).__name__
        finally:
            CallOutput.call = None
            sys.stdout, sys.stderr = stdout, stderr
            namespace.clear()
        answers.write('__SENTINEL__' + status + '\n' + encode(text) + '\n' + encode(printed.getvalue()) + '\n')
        answers.flush()

__karpfen_loop__()
""".trimIndent()
    }
}
