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
package io.karpfen.exec

import io.karpfen.io.karpfen.exec.PersistentPythonSession
import io.karpfen.io.karpfen.exec.PythonAnswer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The check of the process calls in the Python process: every refused form is answered with a refusal that names what
 * the code uses and its line, nothing of refused code runs, the process goes on, code next to each form runs, a code
 * text that does not parse fails as before, and a process started with process calls allowed runs everything.
 */
@Tag("python")
class ProcessCallCheckTest {

    private val session = PersistentPythonSession()

    private val allowing = PersistentPythonSession { PersistentPythonSession.startPython(allowProcessCalls = true) }

    @AfterEach
    fun tearDown() {
        session.close()
        allowing.close()
    }

    // ---- Refused forms ----

    @Test
    fun `an import of a refused module is refused under any name and also for a submodule`() {
        assertRefused(
            "subprocess",
            "import subprocess",
            "import subprocess as sp",
            "import json, subprocess",
            "from subprocess import run",
            "from subprocess import run as start"
        )
        assertRefused("importlib", "import importlib", "import importlib.util", "from importlib import import_module")
        assertRefused("pip", "import pip", "from pip._internal import main")
        assertRefused("ctypes", "import ctypes", "import ctypes.util")
        assertRefused("builtins", "import builtins", "from builtins import open")
        for (module in listOf(
            "pty", "ensurepip", "runpy", "multiprocessing", "_posixsubprocess", "_winapi", "_ctypes", "code", "codeop",
            "distutils"
        )) {
            assertRefused(module, "import $module")
        }
    }

    @Test
    fun `the process functions of os are refused as attributes of the module`() {
        for (function in listOf(
            "system", "popen", "startfile", "execv", "execlp", "execvpe", "spawnl", "spawnvp", "fork", "forkpty",
            "posix_spawn", "posix_spawnp"
        )) {
            assertRefused("os.$function", "import os\nos.$function('x')", line = 2)
        }
        assertRefused("posix.system", "import posix\nposix.system('x')", line = 2)
        assertRefused("nt.system", "import nt\nnt.system('x')", line = 2)
    }

    @Test
    fun `a process function of os is refused through another name of the module`() {
        assertRefused("os.system", "import os as o\no.system('x')", line = 2)
        assertRefused("os.system", "import os\no = os\nq = o\nq.system('x')", line = 4)
        assertRefused("os.system", "import os\n(o := os)\no.system('x')", line = 3)
        assertRefused("os.system", "import os\nos.path.os.system('x')", line = 2)
        assertRefused("os.system", "import os\ngetattr(os, 'system')('x')", line = 2)
    }

    @Test
    fun `a process function of os is refused when it is imported by name or with a star`() {
        assertRefused("os.system", "from os import system", "from os import system as run")
        assertRefused("os.execv", "from os import path, execv")
        assertRefused("os.*", "from os import *")
        assertRefused("posix.spawnv", "from posix import spawnv")
    }

    @Test
    fun `the generic ways to run code are refused in every use`() {
        assertRefused("eval", "eval('1')", "f = eval", "__karpfen_result__ = [eval][0]")
        assertRefused("exec", "exec('x = 1')")
        assertRefused("compile", "compile('1', 'c', 'eval')")
        assertRefused("__import__", "__import__('os')", "__import__('math')")
        assertRefused("__builtins__", "__builtins__", "globals()['__builtins__']")
        assertRefused("__subclasses__", "().__class__.__base__.__subclasses__()")
        assertRefused("__globals__", "def f():\n    pass\nf.__globals__", line = 3)
    }

    @Test
    fun `the process functions of asyncio are refused as attributes and as imports`() {
        assertRefused("create_subprocess_shell", "import asyncio\nasyncio.create_subprocess_shell('x')", line = 2)
        assertRefused("create_subprocess_exec", "from asyncio import create_subprocess_exec")
        assertRefused("subprocess", "import asyncio\nasyncio.subprocess", line = 2)
        assertRefused(
            "subprocess_shell", "import asyncio\nasyncio.new_event_loop().subprocess_shell(None, 'x')", line = 2
        )
    }

    @Test
    fun `the expressions inside an f-string are checked`() {
        assertRefused("__import__", "__karpfen_result__ = f\"{__import__('os').getcwd()}\"")
        assertRefused("os.system", "import os\n__karpfen_result__ = f'{os.system(\"x\")}'", line = 2)
    }

    @Test
    fun `the refusal names the first use in the code`() {
        assertRefused("os.system", "x = 1\nimport os\nos.system('a')\nimport subprocess", line = 3)
        assertRefused("eval", "x = eval('1') + int(exec('2'))")
    }

    // ---- What a refusal does ----

    @Test
    fun `nothing of refused code runs and the process goes on`() {
        assertEquals("1", session.execute("__karpfen_result__ = 1").result)
        val pid = session.pid()

        val answer = session.execute("print('ran')\nimport subprocess\n__karpfen_result__ = 2")

        assertEquals(PythonAnswer(null, "", refusal = refusal("subprocess", 2)), answer)
        assertEquals("3", session.execute("__karpfen_result__ = 3").result)
        assertEquals(pid, session.pid())
    }

    @Test
    fun `refused code is refused again in the next call`() {
        repeat(2) {
            assertEquals(refusal("subprocess", 1), session.execute("import subprocess").refusal)
        }
    }

    @Test
    fun `code that does not parse fails with its syntax error as before`() {
        val answer = session.execute("import subprocess\nx = (")

        assertEquals(null, answer.refusal)
        assertTrue(answer.error!!.startsWith("SyntaxError"), answer.error)
    }

    // ---- Allowed code ----

    @Test
    fun `code next to each refused form runs`() {
        val allowed = mapOf(
            "__karpfen_result__ = \"import subprocess; os.system('pip install x'); eval(__import__)\"" to
                "import subprocess; os.system('pip install x'); eval(__import__)",
            "__karpfen_result__ = f\"{'subprocess'} {'eval'}\"" to "subprocess eval",
            "import os\n__karpfen_result__ = os.getcwd() != ''" to "true",
            "import os.path\n__karpfen_result__ = os.path.join('a', 'b')" to "a/b",
            "import random\n__karpfen_result__ = 0 <= random.random() < 1" to "true",
            "import platform\n__karpfen_result__ = platform.system() != ''" to "true",
            "import re\n__karpfen_result__ = re.compile('a+').fullmatch('aa') is not None" to "true",
            "import math\n__karpfen_result__ = getattr(math, 'floor')(2.5)" to "2",
            "d = {'system': 1, 'eval': 2}\n__karpfen_result__ = d['system'] + d['eval']" to "3",
            "class Lamp:\n    system = 'on'\n__karpfen_result__ = Lamp.system" to "on",
            "import json\n__karpfen_result__ = json.dumps({'subprocess': 1})" to "{\"subprocess\": 1}"
        )
        for ((code, result) in allowed) {
            val answer = session.execute(code)
            assertEquals(PythonAnswer(result, ""), answer, code)
        }
    }

    // ---- The setting ----

    @Test
    fun `a process with process calls allowed runs every refused form`() {
        val child = "import subprocess, sys\n" +
            "__karpfen_result__ = subprocess.run([sys.executable, '-c', 'print(5)'], capture_output=True, text=True)" +
            ".stdout.strip()"

        assertEquals("5", allowing.execute(child).result)
        assertEquals("3", allowing.execute("__karpfen_result__ = eval('1 + 2')").result)
        val popen = "import os as o\n__karpfen_result__ = len(o.popen('echo four').read().strip())"
        assertEquals("4", allowing.execute(popen).result)
        assertEquals("true", allowing.execute("import importlib\n__karpfen_result__ = importlib is not None").result)
    }

    /** Asserts that every code text of [codes] is refused for [use] in its [line]. */
    private fun assertRefused(use: String, vararg codes: String, line: Int = 1) {
        for (code in codes) {
            val answer = session.execute(code)
            assertEquals(PythonAnswer(null, "", refusal = refusal(use, line)), answer, code)
        }
    }

    private companion object {

        /** The refusal of a use of [use] in the line [line] of a code text outside a function. */
        fun refusal(use: String, line: Int): String =
            "Code may not use '$use' (line $line). Process calls are switched off; " +
                "engine.allowProcessCalls = true in application.conf allows them."
    }
}
