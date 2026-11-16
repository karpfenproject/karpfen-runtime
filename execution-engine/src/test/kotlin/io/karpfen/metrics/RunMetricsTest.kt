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
package io.karpfen.metrics

import io.karpfen.io.karpfen.metrics.CodeCalls
import io.karpfen.io.karpfen.metrics.CodeWay
import io.karpfen.io.karpfen.metrics.RunMetrics
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The numbers of [RunMetrics] without an engine: totals, mean, maximum, the ring of recent ticks, the time outside
 * Python per tick, the calls per way, differences of snapshots, and updates from many threads at once.
 */
class RunMetricsTest {

    @Test
    fun `before the first tick every number is zero and every way is listed`() {
        val snapshot = RunMetrics().snapshot()

        assertEquals(0, snapshot.ticks)
        assertEquals(0.0, snapshot.meanTickNanos)
        assertEquals(0, snapshot.maxTickNanos)
        assertEquals(emptyList(), snapshot.recentTickNanos)
        assertEquals(CodeWay.entries.associateWith { CodeCalls(0, 0) }, snapshot.calls)
    }

    @Test
    fun `ticks give the total, the mean, the maximum and the latest durations from the oldest on`() {
        val metrics = RunMetrics(recentTicks = 3)

        listOf(5L, 9L, 1L, 7L, 3L).forEach(metrics::tickEnded)
        val snapshot = metrics.snapshot()

        assertEquals(5, snapshot.ticks)
        assertEquals(25, snapshot.tickNanos)
        assertEquals(5.0, snapshot.meanTickNanos)
        assertEquals(9, snapshot.maxTickNanos)
        assertEquals(listOf(1L, 7L, 3L), snapshot.recentTickNanos)
    }

    @Test
    fun `calls are counted and timed per way, and only the calls in a Python process count as Python`() {
        val metrics = RunMetrics()

        metrics.codeRan(CodeWay.COMPUTED, 2)
        metrics.codeRan(CodeWay.COMPUTED, 3)
        metrics.codeRan(CodeWay.OPEN_PROCESS, 40)
        metrics.codeRan(CodeWay.CACHE, 1)
        val snapshot = metrics.snapshot()

        assertEquals(CodeCalls(2, 5), snapshot.calls[CodeWay.COMPUTED])
        assertEquals(CodeCalls(1, 40), snapshot.calls[CodeWay.OPEN_PROCESS])
        assertEquals(CodeCalls(1, 1), snapshot.calls[CodeWay.CACHE])
        assertEquals(40, snapshot.pythonNanos)
    }

    @Test
    fun `the time outside Python of a tick is its duration minus the Python calls since the tick before`() {
        val metrics = RunMetrics()

        metrics.codeRan(CodeWay.OPEN_PROCESS, 30)
        metrics.codeRan(CodeWay.COMPUTED, 5)
        metrics.tickEnded(100)
        metrics.tickEnded(20)
        metrics.codeRan(CodeWay.OPEN_PROCESS, 50)
        metrics.codeRan(CodeWay.OPEN_PROCESS, 50)
        metrics.tickEnded(60)

        assertEquals(70 + 20 + 0, metrics.snapshot().outsidePythonNanos)
    }

    @Test
    fun `the difference of two snapshots holds the ticks and calls between them`() {
        val metrics = RunMetrics()
        metrics.codeRan(CodeWay.OPEN_PROCESS, 10)
        metrics.tickEnded(40)
        val earlier = metrics.snapshot()
        metrics.codeRan(CodeWay.OPEN_PROCESS, 5)
        metrics.tickEnded(8)
        metrics.tickEnded(12)

        val between = metrics.snapshot().since(earlier)

        assertEquals(2, between.ticks)
        assertEquals(20, between.tickNanos)
        assertEquals(10.0, between.meanTickNanos)
        assertEquals(15, between.outsidePythonNanos)
        assertEquals(CodeCalls(1, 5), between.calls[CodeWay.OPEN_PROCESS])
        assertEquals(CodeCalls(0, 0), between.calls[CodeWay.COMPUTED])
    }

    @Test
    fun `calls from many threads at once are all counted`() {
        val metrics = RunMetrics()
        val start = CountDownLatch(1)

        val threads = (1..8).map {
            thread {
                start.await()
                repeat(10_000) { metrics.codeRan(CodeWay.OPEN_PROCESS, 1) }
            }
        }
        start.countDown()
        repeat(1_000) { metrics.tickEnded(1) }
        threads.forEach { it.join() }
        val snapshot = metrics.snapshot()

        assertEquals(CodeCalls(80_000, 80_000), snapshot.calls[CodeWay.OPEN_PROCESS])
        assertEquals(1_000, snapshot.ticks)
    }

    @Test
    fun `the ring of recent ticks needs room for one tick`() {
        assertFailsWith<IllegalArgumentException> { RunMetrics(recentTicks = 0) }
    }
}
