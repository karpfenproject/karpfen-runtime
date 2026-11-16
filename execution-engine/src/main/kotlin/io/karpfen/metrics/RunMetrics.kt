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
package io.karpfen.io.karpfen.metrics

/**
 * The numbers of one engine over its whole life: its ticks and the calls of code its state machine instances made.
 *
 * The memory stays bounded however long the engine runs: totals, a maximum and a ring of the durations of the last
 * [recentTicks] ticks. Every update takes one short lock, which costs far less than a tick, so the numbers stay on in
 * every run.
 *
 * The time a tick spends outside Python is the duration of the tick minus the time its calls waited for Python. The
 * calls of a tick are the calls recorded since the end of the tick before; the engine runs code only inside ticks, so
 * these are the calls of the tick. While the feature TrueParallel is on, branches wait for Python at the same time, and
 * their waits together can be longer than the tick; the time outside Python of such a tick is then counted as zero.
 *
 * @property recentTicks how many of the latest tick durations [snapshot] gives.
 */
class RunMetrics(private val recentTicks: Int = RECENT_TICKS) : RunMetricsRecorder {

    init {
        require(recentTicks > 0) { "The number of recent ticks must be positive" }
    }

    private val lock = Any()
    private var ticks = 0L
    private var tickNanos = 0L
    private var maxTickNanos = 0L
    private var outsidePythonNanos = 0L
    private val recent = LongArray(recentTicks)
    private val calls = LongArray(CodeWay.entries.size)
    private val codeNanos = LongArray(CodeWay.entries.size)

    /** The time waited for Python over all calls at the end of the latest tick. */
    private var pythonNanosAtTickEnd = 0L

    override fun tickEnded(durationNanos: Long) = synchronized(lock) {
        val python = pythonNanos()
        val pythonInTick = python - pythonNanosAtTickEnd
        pythonNanosAtTickEnd = python
        recent[(ticks % recentTicks).toInt()] = durationNanos
        ticks++
        tickNanos += durationNanos
        if (durationNanos > maxTickNanos) maxTickNanos = durationNanos
        outsidePythonNanos += (durationNanos - pythonInTick).coerceAtLeast(0L)
    }

    override fun codeRan(way: CodeWay, durationNanos: Long) = synchronized(lock) {
        calls[way.ordinal]++
        codeNanos[way.ordinal] += durationNanos
    }

    /** The numbers as they are now, with the recent tick durations from the oldest to the newest. */
    fun snapshot(): RunMetricsSnapshot = synchronized(lock) {
        val kept = minOf(ticks, recentTicks.toLong()).toInt()
        val recentInOrder = List(kept) { i -> recent[((ticks - kept + i) % recentTicks).toInt()] }
        RunMetricsSnapshot(
            ticks = ticks,
            tickNanos = tickNanos,
            maxTickNanos = maxTickNanos,
            recentTickNanos = recentInOrder,
            outsidePythonNanos = outsidePythonNanos,
            calls = CodeWay.entries.associateWith { CodeCalls(calls[it.ordinal], codeNanos[it.ordinal]) }
        )
    }

    private fun pythonNanos(): Long =
        codeNanos[CodeWay.NEW_PROCESS.ordinal] + codeNanos[CodeWay.OPEN_PROCESS.ordinal]

    companion object {
        /** The number of recent tick durations kept by default. */
        const val RECENT_TICKS = 100
    }
}

/**
 * The calls of code that ran in one way.
 *
 * @property count the number of calls.
 * @property nanos the time the calls took together, in nanoseconds.
 */
data class CodeCalls(val count: Long, val nanos: Long) {

    /** The calls of this minus those of [earlier]. */
    operator fun minus(earlier: CodeCalls): CodeCalls = CodeCalls(count - earlier.count, nanos - earlier.nanos)
}

/**
 * The numbers of an engine at one moment, as [RunMetrics.snapshot] gives them. Durations are in nanoseconds.
 *
 * @property ticks the number of ticks.
 * @property tickNanos the duration of all ticks together.
 * @property maxTickNanos the duration of the longest tick, 0 before the first tick.
 * @property recentTickNanos the durations of the latest ticks, from the oldest to the newest.
 * @property outsidePythonNanos the time the ticks spent outside Python.
 * @property calls the calls of code per way, with an entry for every [CodeWay].
 */
data class RunMetricsSnapshot(
    val ticks: Long,
    val tickNanos: Long,
    val maxTickNanos: Long,
    val recentTickNanos: List<Long>,
    val outsidePythonNanos: Long,
    val calls: Map<CodeWay, CodeCalls>
) {

    /** The mean duration of a tick, 0 before the first tick. */
    val meanTickNanos: Double
        get() = if (ticks == 0L) 0.0 else tickNanos.toDouble() / ticks

    /** The time all calls waited for Python. */
    val pythonNanos: Long
        get() = calls.filterKeys { it.waitsForPython }.values.sumOf { it.nanos }

    /**
     * The numbers of the ticks and calls between [earlier] and this snapshot of the same engine: the counts and totals
     * are differences. The maximum and the recent durations are those of this snapshot, since the ticks they belong to
     * cannot be told apart from the earlier ones.
     */
    fun since(earlier: RunMetricsSnapshot): RunMetricsSnapshot = RunMetricsSnapshot(
        ticks = ticks - earlier.ticks,
        tickNanos = tickNanos - earlier.tickNanos,
        maxTickNanos = maxTickNanos,
        recentTickNanos = recentTickNanos,
        outsidePythonNanos = outsidePythonNanos - earlier.outsidePythonNanos,
        calls = calls.mapValues { (way, now) -> now - (earlier.calls[way] ?: CodeCalls(0, 0)) }
    )
}
