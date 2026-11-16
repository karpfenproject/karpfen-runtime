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

import io.karpfen.dsl.instance.Model
import io.karpfen.io.karpfen.metrics.RunMetrics
import io.karpfen.io.karpfen.metrics.RunMetricsSnapshot

/**
 * An [EnvironmentLookup] over environments a test declares, without the runtime's registry.
 */
class FakeEnvironments : EnvironmentLookup {

    /** The keys of the created environments. */
    private val created = mutableSetOf<String>()

    /** The uploaded model per environment. */
    private val models = mutableMapOf<String, Model>()

    /** The observed environment per activated environment. */
    private val activated = mutableMapOf<String, ObservedEnvironment>()

    /** Declares the environment [envKey] as created, with [model] uploaded when given. */
    fun create(envKey: String, model: Model? = null) {
        created.add(envKey)
        if (model != null) models[envKey] = model
    }

    /** Declares the created environment [envKey] as activated, observed through [environment]. */
    fun activate(envKey: String, environment: ObservedEnvironment) {
        activated[envKey] = environment
    }

    /** True when the test created [envKey]. */
    override fun exists(envKey: String): Boolean = envKey in created

    /** The model the test uploaded to [envKey], if any. */
    override fun uploadedModel(envKey: String): Model? = models[envKey]

    /** The observed environment the test activated as [envKey], if any. */
    override fun observed(envKey: String): ObservedEnvironment? = activated[envKey]
}

/**
 * An [ObservedEnvironment] that answers every snapshot request with [snapshot], every request for numbers with
 * [metrics], and records the timeouts it was asked to wait.
 *
 * @property phase the phase the environment reports.
 * @property snapshot the snapshot answered, or `null` to act as an environment whose tick never ends.
 * @property metrics the numbers answered by [metrics]; by default none, as before the first tick.
 * @property resultCaching the answer of [ObservedEnvironment.resultCaching]; on by default, as in the runtime.
 */
class FakeObservedEnvironment(
    override val phase: EnvironmentPhase,
    private val snapshot: ModelSnapshot?,
    private val metrics: RunMetricsSnapshot = NO_METRICS,
    override val resultCaching: Boolean = true
) : ObservedEnvironment {

    /** The timeouts of every snapshot request, in request order. */
    val requestedTimeouts = mutableListOf<Long>()

    /** Records [timeoutMs] and answers the configured snapshot. */
    override fun snapshotBetweenTicks(timeoutMs: Long): ModelSnapshot? {
        requestedTimeouts.add(timeoutMs)
        return snapshot
    }

    /** Answers the configured numbers. */
    override fun metrics(): RunMetricsSnapshot = metrics

    companion object {
        /** The numbers of an engine that has not ticked. */
        val NO_METRICS: RunMetricsSnapshot = RunMetrics().snapshot()
    }
}
