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

import io.karpfen.Engine
import io.karpfen.EngineTraceLogger
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.io.karpfen.exec.LruResultCache
import io.karpfen.io.karpfen.exec.ResultCache
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.messages.EventBus
import kotlin.reflect.KClass

/**
 * What a benchmark runs: the texts of a metamodel, a model and state machines, the features that are on and the time
 * to live of the events. [engine] reads the texts and builds a new engine with a fresh model each time, so every trial
 * starts from the same state.
 *
 * @property metamodel the KMeta text.
 * @property model the KModel text.
 * @property attached the KStates texts of the attached state machines by the key of their object.
 * @property definitions the KStates texts of the attachable state machines that `behaves after` names.
 * @property features the features that are on, besides TickByTick, which the [TickDriver] switches on.
 * @property eventTtlMs the time to live of the events on the bus; 0 keeps events until they are consumed.
 * @property resultCaching whether the engine answers a code text it ran in Python before from a result cache with the
 *   bounds that `application.conf` of the runtime ships. Off by default, so a benchmark measures the calls of Python.
 */
class Scenario(
    private val metamodel: String,
    private val model: String,
    private val attached: Map<String, String>,
    private val definitions: List<String> = emptyList(),
    private val features: List<KClass<out Feature>> = emptyList(),
    private val eventTtlMs: Long = 0L,
    private val resultCaching: Boolean = false
) {

    /**
     * Builds a new engine over a fresh model, with a new feature manager that has [features] on. Like an environment of
     * the server, the engine has a trace logger that writes neither a file nor to the console but keeps its entries.
     */
    fun engine(): Engine {
        val meta = KmetaReader().read(metamodel.trimIndent()).modelOrThrow("benchmark metamodel")
        val featureManager = FeatureManager()
        features.forEach { check(featureManager.requestFeatureActivation(it)) { "Feature ${it.simpleName} is not on" } }
        return Engine(
            metamodel = meta,
            model = KmodelReader(meta).read(model.trimIndent()).modelOrThrow("benchmark model"),
            statemachineMap = attached.mapValues { (key, text) -> stateMachine(text, key) },
            tickDelayMS = 0,
            eventBus = EventBus(eventTtlMs),
            traceLogger = EngineTraceLogger("benchmark", logFilePath = null, consoleOutput = false),
            featureManager = featureManager,
            stateMachineDefinitions = definitions.map { stateMachine(it, "definition") }.associateBy { it.name!! },
            resultCache = if (resultCaching) LruResultCache(CACHE_ENTRIES, CACHE_BYTES) else ResultCache.NONE
        )
    }

    private fun stateMachine(text: String, source: String) =
        KstatesReader().read(text.trimIndent()).modelOrThrow("benchmark state machine of $source")

    private companion object {
        /** The bounds of the result cache in the `application.conf` of the runtime. */
        const val CACHE_ENTRIES = 10_000
        const val CACHE_BYTES = 64L * 1024 * 1024
    }
}
