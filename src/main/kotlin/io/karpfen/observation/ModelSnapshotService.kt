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

/**
 * Answers model snapshots of environments in every phase.
 *
 * An environment with an execution thread is read through its tick gate, so the snapshot sees the model between two
 * ticks; the wait for a tick boundary is bounded by [tickWaitMs], so a tick that hangs yields a clear error instead of
 * a hanging request. A configuring environment has no engine, and nothing but a new upload, which only swaps the
 * model reference, changes its model; its uploaded model is written on the calling thread at revision 0. If an
 * execution thread appears meanwhile, that result is discarded and the model is read through the gate instead.
 *
 * Failures are exceptions whose type the HTTP layer maps to a status: [IllegalArgumentException] for an unknown
 * environment (400), [IllegalStateException] for a missing model, a missed tick boundary or a reference loop (409).
 *
 * @property environments where environments are found.
 * @property tickWaitMs how long a snapshot waits at most for a running tick to end, in milliseconds.
 */
class ModelSnapshotService(
    private val environments: EnvironmentLookup,
    private val tickWaitMs: Long
) {

    /**
     * The snapshot of the environment [envKey].
     *
     * @throws IllegalArgumentException when the environment does not exist.
     * @throws IllegalStateException when a configuring environment has no model yet, when no tick boundary came within
     *   [tickWaitMs], or when the model holds a reference loop.
     */
    fun snapshot(envKey: String): ModelSnapshot {
        if (!environments.exists(envKey)) {
            throw IllegalArgumentException("Environment with key $envKey does not exist")
        }
        environments.observed(envKey)?.let { return betweenTicks(envKey, it) }
        val model = environments.uploadedModel(envKey)
            ?: throw IllegalStateException("Environment with key $envKey has no model")
        val roots = model.objects.map(ModelJsonWriter::write)
        environments.observed(envKey)?.let { return betweenTicks(envKey, it) }
        return ModelSnapshot(envKey, EnvironmentPhase.CONFIGURING, 0L, emptyMap(), roots)
    }

    /** Reads the snapshot of the activated environment [envKey] through its tick gate. */
    private fun betweenTicks(envKey: String, environment: ObservedEnvironment): ModelSnapshot =
        environment.snapshotBetweenTicks(tickWaitMs) ?: throw IllegalStateException(
            "Environment with key $envKey did not reach a tick boundary within $tickWaitMs ms"
        )

    companion object {
        /** The default wait for a tick boundary: ten seconds. */
        const val DEFAULT_TICK_WAIT_MS = 10_000L
    }
}
