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
package io.karpfen.observation

import io.karpfen.io.karpfen.exec.ModelJsonWriter
import io.karpfen.io.karpfen.metrics.CodeWay
import io.karpfen.io.karpfen.metrics.RunMetricsSnapshot
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Answers the numbers of activated environments: their ticks and their calls of code.
 *
 * The numbers are read without waiting for a tick boundary, since the engine keeps them consistent under a lock of
 * their own, so the answer comes at once also while a tick runs or hangs, while the environment is stopped and while a
 * breakpoint halts it.
 *
 * Failures are exceptions whose type the HTTP layer maps to a status: [IllegalArgumentException] for an unknown
 * environment (400) and [IllegalStateException] for an environment that has no engine yet (409).
 *
 * @property environments where environments are found.
 */
class RunMetricsService(private val environments: EnvironmentLookup) {

    /**
     * The numbers of the environment [envKey].
     *
     * @throws IllegalArgumentException when the environment does not exist.
     * @throws IllegalStateException when the environment was not activated.
     */
    fun metrics(envKey: String): RunMetricsAnswer {
        if (!environments.exists(envKey)) {
            throw IllegalArgumentException("Environment with key $envKey does not exist")
        }
        val environment = environments.observed(envKey)
            ?: throw IllegalStateException("Environment with key $envKey is not active")
        return RunMetricsAnswer(envKey, environment.phase, environment.metrics(), environment.resultCaching)
    }
}

/**
 * The numbers of one environment as `GET /observatory/metrics` answers them.
 *
 * @property environmentKey the environment the numbers belong to.
 * @property phase the phase of the environment when the numbers were read.
 * @property metrics the numbers of its engine.
 * @property resultCaching whether its engine answers code it ran in Python before from its result cache.
 */
data class RunMetricsAnswer(
    val environmentKey: String,
    val phase: EnvironmentPhase,
    val metrics: RunMetricsSnapshot,
    val resultCaching: Boolean
) {

    /**
     * Writes the answer as one JSON object with the members `environmentKey`, `phase`, `ticks`, `tickMs`, `pythonMs`,
     * `outsidePythonMs`, `calls` and `resultCaching` in this order and without whitespace. `tickMs` holds `total`,
     * `mean`, `max` and `recent`; `calls` holds an object with `count` and `ms` for each way code runs. Durations are milliseconds, exact
     * to the nanosecond, written as plain decimal numbers without trailing zeros.
     */
    fun toJson(): String {
        val recent = metrics.recentTickNanos.joinToString(",") { ms(it) }
        val calls = CodeWay.entries.joinToString(",") { way ->
            val call = metrics.calls.getValue(way)
            "${ModelJsonWriter.quote(way.wireName)}:{\"count\":${call.count},\"ms\":${ms(call.nanos)}}"
        }
        val mean = if (metrics.ticks == 0L) "0" else ms(
            BigDecimal.valueOf(metrics.tickNanos).divide(BigDecimal.valueOf(metrics.ticks), 0, RoundingMode.HALF_UP)
        )
        return "{\"environmentKey\":${ModelJsonWriter.quote(environmentKey)}," +
            "\"phase\":${ModelJsonWriter.quote(phase.wireName)}," +
            "\"ticks\":${metrics.ticks}," +
            "\"tickMs\":{\"total\":${ms(metrics.tickNanos)},\"mean\":$mean,\"max\":${ms(metrics.maxTickNanos)}," +
            "\"recent\":[$recent]}," +
            "\"pythonMs\":${ms(metrics.pythonNanos)}," +
            "\"outsidePythonMs\":${ms(metrics.outsidePythonNanos)}," +
            "\"calls\":{$calls}," +
            "\"resultCaching\":$resultCaching}"
    }

    private fun ms(nanos: Long): String = ms(BigDecimal.valueOf(nanos))

    private fun ms(nanos: BigDecimal): String = nanos.movePointLeft(6).stripTrailingZeros().toPlainString()
}
