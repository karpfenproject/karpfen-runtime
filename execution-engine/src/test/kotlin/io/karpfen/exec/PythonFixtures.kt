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

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.reader.ParseResult
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.dsl.states.StateMachine
import io.karpfen.io.karpfen.exec.CodeOutput
import io.karpfen.io.karpfen.exec.MacroProcessor
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import kotlin.test.assertTrue

/**
 * The lamp world of the tests of the Python path: a room with three lamps, each with a bulb, and the texts and numbers
 * that are hard to hand to Python. Lamps know partner lamps and bulbs know the lamps that own them, so the tests can build
 * objects that know each other.
 */
object PythonFixtures {

    val METAMODEL = """
        type "Room" "A room with lamps" {
            prop("log", list("string"))
            has("lamps", list("Lamp"))
        }
        type "Lamp" "A lamp" {
            prop("label", "string")
            prop("watts", "number")
            prop("on", "boolean")
            prop("readings", list("number"))
            prop("log", list("string"))
            has("bulb", "Bulb")
            knows("partners", list("Lamp"))
        }
        type "Bulb" "A light bulb" {
            prop("label", "string")
            knows("owners", list("Lamp"))
        }
    """.trimIndent()

    val MODEL = """
        make object "room":"Room" {
            has("lamps") -> make object "a":"Lamp" {
                prop("label") -> "plain"
                prop("watts") -> "40"
                prop("on") -> "false"
                has("bulb") -> make object "bulbA":"Bulb" { prop("label") -> "bulb a" }
            }
            has("lamps") -> make object "b":"Lamp" {
                prop("label") -> "plain"
                prop("watts") -> "60"
                prop("on") -> "false"
                has("bulb") -> make object "bulbB":"Bulb" { prop("label") -> "bulb b" }
            }
            has("lamps") -> make object "c":"Lamp" {
                prop("label") -> "plain"
                prop("watts") -> "75"
                prop("on") -> "false"
                has("bulb") -> make object "bulbC":"Bulb" { prop("label") -> "bulb c" }
            }
        }
    """.trimIndent()

    /**
     * Texts with what can break a Python literal or the way back: both quotes, backslashes, line breaks of every kind,
     * tabs, control characters, characters outside ASCII and outside the basic plane, white space at both ends, and
     * the marks of the code language itself.
     */
    val HARD_TEXTS = listOf(
        "say \"hi\"",
        "it's",
        "\"'\"'",
        "back\\slash \\n not a line break \\",
        "two\nlines",
        "windows\r\nline",
        "carriage\rreturn",
        "\ttabbed\t",
        "  spaces around  ",
        "trailing line break\n",
        "bell \u0007 escape \u001B delete \u007F",
        "Grüße, Ünïcödé, 日本語, 💡",
        "  line separator   paragraph separator \u0085 next line",
        "$(label) and @macro(x) and {\"__id__\": \"a\"}",
        "\"\"\" triple ''' quotes",
        "",
        " "
    )

    /** Reads [result] and fails when the reader found an error. */
    fun <M : Any> accept(result: ParseResult<M>): M {
        assertTrue(!result.hasErrors, "errors: ${result.errors}")
        return result.model!!
    }

    fun metamodel(): Metamodel = accept(KmetaReader().read(METAMODEL))

    fun model(metamodel: Metamodel): Model = accept(KmodelReader(metamodel).read(MODEL))

    /** Reads a state machine text. */
    fun stateMachine(text: String): StateMachine = accept(KstatesReader().read(text.trimIndent()))

    /**
     * A macro processor on a fresh model whose context object is [context], with the macros of [stateMachine], that
     * hands what code prints to [output].
     */
    fun processor(
        context: String = "a",
        stateMachine: String? = null,
        output: CodeOutput = CodeOutput.CONSOLE
    ): MacroProcessor {
        val metamodel = metamodel()
        val model = model(metamodel)
        val macros = stateMachine?.let { stateMachine(it).macros } ?: emptyList()
        return MacroProcessor(
            metamodel, model, macros, context, ModelQueryProcessor(metamodel, model), output = output
        )
    }

    /** The process ids of the Python processes this JVM started and that still run. */
    fun pythonProcesses(): Set<Long> = ProcessHandle.current().descendants()
        .filter { it.isAlive }
        .filter { handle -> handle.info().command().map { it.contains("python") }.orElse(false) }
        .map { it.pid() }
        .toList()
        .toSet()

    /** Waits up to 10 s for [reached] and fails with [what] when it does not come. */
    fun await(what: String, reached: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!reached()) {
            if (System.currentTimeMillis() > deadline) kotlin.test.fail("Not reached in time: $what")
            Thread.sleep(5)
        }
    }
}
