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

import io.karpfen.dsl.instance.DataObject
import io.karpfen.io.karpfen.exec.CodeOutput
import io.karpfen.io.karpfen.exec.LruResultCache
import io.karpfen.io.karpfen.exec.MacroProcessor
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import io.karpfen.io.karpfen.exec.PythonExecutionException
import io.karpfen.io.karpfen.exec.ResultCache
import io.karpfen.io.karpfen.metrics.CodeWay
import io.karpfen.io.karpfen.metrics.RunMetrics
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How a [MacroProcessor] uses its [ResultCache]: a code text that ran in Python before is answered from the cache, a
 * changed value is a new text, a hit is read for the target type of its call and builds a new object each time, a failed
 * call is not stored, and without a cache every call runs Python.
 */
@Tag("python")
class ResultCacheCallsTest {

    private val metrics = RunMetrics()
    private val printed = CopyOnWriteArrayList<String>()
    private val processors = mutableListOf<MacroProcessor>()

    @AfterEach
    fun close() {
        processors.forEach { it.pythonSessions.close() }
    }

    @Test
    fun `the same code text runs Python once and then comes from the cache without printing again`() {
        val processor = processor(LruResultCache(100, 1_000_000))

        repeat(3) {
            assertEquals("plain!", processor.executeInlineMacro("print('ran')\nreturn $(label) + '!'", "string"))
        }

        assertEquals(1L, count(CodeWay.OPEN_PROCESS))
        assertEquals(2L, count(CodeWay.CACHE))
        assertEquals(listOf("ran\n"), printed)
    }

    @Test
    fun `a changed value makes a new code text that runs in Python`() {
        val processor = processor(LruResultCache(100, 1_000_000))

        assertEquals(41.0, processor.executeInlineMacro("return abs($(watts)) + 1", "number"))
        processor.modelQueryProcessor.setScalar("a", "watts", 50.0)
        assertEquals(51.0, processor.executeInlineMacro("return abs($(watts)) + 1", "number"))

        assertEquals(2L, count(CodeWay.OPEN_PROCESS))
        assertEquals(0L, count(CodeWay.CACHE))
    }

    @Test
    fun `a call that fails is not stored and fails again in Python`() {
        val processor = processor(LruResultCache(100, 1_000_000))

        repeat(2) {
            assertThrows(PythonExecutionException::class.java) {
                processor.executeInlineMacro("return 1 / 0", "number")
            }
        }

        assertEquals(2L, count(CodeWay.OPEN_PROCESS))
        assertEquals(0L, count(CodeWay.CACHE))
    }

    @Test
    fun `code without a result is stored as no result`() {
        val processor = processor(LruResultCache(100, 1_000_000))

        repeat(2) { assertEquals(null, processor.executeInlineMacro("x = 1", "number")) }

        assertEquals(1L, count(CodeWay.OPEN_PROCESS))
        assertEquals(1L, count(CodeWay.CACHE))
    }

    @Test
    fun `without a cache every call runs Python`() {
        val processor = processor(ResultCache.NONE)

        repeat(3) { processor.executeInlineMacro("return abs($(watts))", "number") }

        assertEquals(3L, count(CodeWay.OPEN_PROCESS))
        assertEquals(0L, count(CodeWay.CACHE))
    }

    @Test
    fun `instances that share a cache share its results`() {
        val cache = LruResultCache(100, 1_000_000)
        val first = processor(cache, context = "a")
        val second = processor(cache, context = "b")

        first.executeInlineMacro("return abs(-2)", "number")
        second.executeInlineMacro("return abs(-2)", "number")

        assertEquals(1L, count(CodeWay.OPEN_PROCESS))
        assertEquals(1L, count(CodeWay.CACHE))
    }

    @Test
    fun `a hit is read for the target type of the call that hits`() {
        val processor = processor(LruResultCache(100, 1_000_000))

        assertEquals(4.0, processor.executeInlineMacro("return abs(-4)", "number"))
        assertEquals("4", processor.executeInlineMacro("return abs(-4)", "string"))

        assertEquals(1L, count(CodeWay.OPEN_PROCESS))
        assertEquals(1L, count(CodeWay.CACHE))
    }

    @Test
    fun `a hit whose result is a new object gives a new object with a fresh id each time`() {
        val processor = processor(LruResultCache(100, 1_000_000))

        val objects = List(3) {
            processor.executeInlineMacro("return {'label': 'made'}", "Bulb") as DataObject
        }

        assertEquals(listOf("made", "made", "made"), objects.map { it.getProp("label").single() })
        assertEquals(3, objects.map { it.id }.toSet().size, "ids: ${objects.map { it.id }}")
        assertEquals(3, objects.map { System.identityHashCode(it) }.toSet().size)
        assertEquals(1L, count(CodeWay.OPEN_PROCESS))
        assertEquals(2L, count(CodeWay.CACHE))
    }

    private fun count(way: CodeWay): Long = metrics.snapshot().calls.getValue(way).count

    private fun processor(cache: ResultCache, context: String = "a"): MacroProcessor {
        val metamodel = PythonFixtures.metamodel()
        val model = PythonFixtures.model(metamodel)
        return MacroProcessor(
            metamodel, model, emptyList(), context, ModelQueryProcessor(metamodel, model), metrics,
            resultCache = cache,
            output = CodeOutput { _, text -> printed += text }
        ).also { processors += it }
    }
}
