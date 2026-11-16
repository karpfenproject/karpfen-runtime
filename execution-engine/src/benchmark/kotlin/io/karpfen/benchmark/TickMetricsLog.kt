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
package io.karpfen.benchmark

import io.karpfen.io.karpfen.metrics.CodeWay
import io.karpfen.io.karpfen.metrics.RunMetricsSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.openjdk.jmh.infra.BenchmarkParams
import java.io.File

/**
 * Writes the numbers the engine measured during the measured iterations of one trial, as one JSON line per trial, to
 * the file of the system property `karpfen.benchmark.metrics` or else `build/reports/benchmarks/tick-metrics.jsonl`
 * below the working directory of the benchmark, which is the folder of the project `execution-engine`.
 *
 * JMH reports the time of an operation, which includes the driver. The line adds what the engine itself measured per
 * tick: the mean duration, the time in Python and outside it, and the calls per way with their mean time, so the
 * breakdown of every benchmark comes with its score. A benchmark that measures something else per tick adds its own
 * entries with [extra].
 */
class TickMetricsLog {

    private var measured: RunMetricsSnapshot? = null
    private val extras = JSONObject()

    /** Adds the numbers between [before] and [after] of a measured iteration to the trial. */
    fun add(before: RunMetricsSnapshot, after: RunMetricsSnapshot) {
        val between = after.since(before)
        measured = measured?.let { sum(it, between) } ?: between
    }

    /** Adds a value of the benchmark's own to the line: [name] gets the list of all values added under it. */
    fun extra(name: String, value: Double) {
        if (!extras.has(name)) extras.put(name, JSONArray())
        extras.getJSONArray(name).put(value)
    }

    /** Appends the line of the trial of [params] to the file and forgets the trial. */
    fun write(params: BenchmarkParams) {
        val line = JSONObject()
        line.put("benchmark", params.benchmark)
        line.put("params", JSONObject(params.paramsKeys.associateWith { params.getParam(it) }))
        measured?.let { numbers ->
            val ticks = numbers.ticks.toDouble()
            line.put("ticks", numbers.ticks)
            if (ticks > 0) {
                line.put("tickMs", ms(numbers.tickNanos) / ticks)
                line.put("pythonMsPerTick", ms(numbers.pythonNanos) / ticks)
                line.put("outsidePythonMsPerTick", ms(numbers.outsidePythonNanos) / ticks)
                val calls = JSONObject()
                for (way in CodeWay.entries) {
                    val call = numbers.calls.getValue(way)
                    if (call.count == 0L) continue
                    calls.put(way.wireName, JSONObject()
                        .put("perTick", call.count / ticks)
                        .put("msPerCall", ms(call.nanos) / call.count))
                }
                line.put("calls", calls)
            }
        }
        for (name in extras.keySet()) line.put(name, extras.get(name))
        val file = File(System.getProperty("karpfen.benchmark.metrics") ?: "build/reports/benchmarks/tick-metrics.jsonl")
        file.absoluteFile.parentFile.mkdirs()
        file.appendText(line.toString() + "\n")
        measured = null
        extras.keySet().toList().forEach(extras::remove)
    }

    private fun sum(a: RunMetricsSnapshot, b: RunMetricsSnapshot) = RunMetricsSnapshot(
        ticks = a.ticks + b.ticks,
        tickNanos = a.tickNanos + b.tickNanos,
        maxTickNanos = maxOf(a.maxTickNanos, b.maxTickNanos),
        recentTickNanos = b.recentTickNanos,
        outsidePythonNanos = a.outsidePythonNanos + b.outsidePythonNanos,
        calls = a.calls.mapValues { (way, calls) ->
            val other = b.calls.getValue(way)
            calls.copy(count = calls.count + other.count, nanos = calls.nanos + other.nanos)
        }
    )

    private fun ms(nanos: Long): Double = nanos / 1_000_000.0
}
