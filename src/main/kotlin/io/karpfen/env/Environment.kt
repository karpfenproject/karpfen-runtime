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
package io.karpfen.env

import io.karpfen.dsl.instance.Model
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.StateMachine

data class Environment(
    val key: String,
    var metamodel: Metamodel? = null,
    var model: Model? = null,
    /** Metamodel describing the allowed event payloads (parsed from an EVENTS.kmeta). Null when no payloads are used. */
    var eventMetamodel: Metamodel? = null,
    /** Raw EVENTS.kmeta source text, kept for observability. */
    var eventDefinitionsSource: String? = null,
    val stateMachines: MutableMap<String, StateMachine> = mutableMapOf(), // modelElement -> attached state machine
    val stateMachineSources: MutableMap<String, String> = mutableMapOf(), // modelElement -> raw kStates DSL text
    /** The attachable state machines by their name, uploaded without `attachedTo`, in upload order. */
    val stateMachineDefinitions: MutableMap<String, StateMachine> = mutableMapOf(),
    /** The KStates text of every attachable state machine, by its name. */
    val stateMachineDefinitionSources: MutableMap<String, String> = mutableMapOf(),
    /**
     * The fragments by their name, uploaded without `attachedTo`, in upload order. Container states of the other state
     * machines use them; [ComposedStateMachines] flattens them in when the environment is activated.
     */
    val stateMachineFragments: MutableMap<String, StateMachine> = mutableMapOf(),
    /** The KStates text of every fragment, by its name. */
    val stateMachineFragmentSources: MutableMap<String, String> = mutableMapOf(),
    var tickDelayMS: Int = 1000,
    /** Time-to-live for events in milliseconds. 0 means events live forever. */
    var eventTtlMs: Long = 0L,
    /** When true (default), events are consumed only when a transition fires. When false, consumed on condition read. */
    var eventConsumptionOnFire: Boolean = true,
    /**
     * When true, the engine of the environment answers a code text that it ran in Python before with the stored result
     * instead of running it again. A client switches it before the activation with `/setResultCaching`.
     */
    var resultCaching: Boolean = true,
    /** The most results the cache of [resultCaching] holds. */
    val resultCacheMaxEntries: Int = 10_000,
    /** The most memory, in bytes, the results of the cache of [resultCaching] may take. */
    val resultCacheMaxBytes: Long = 64L * 1024 * 1024,
    /**
     * True to let the code of the environment use the ways to start a process and the generic ways to run code, which
     * its Python processes refuse otherwise. Taken from `engine.allowProcessCalls` in `application.conf`.
     */
    val allowProcessCalls: Boolean = false,
    val objectObservations: MutableList<Observation> = mutableListOf(),
    val domainListeners: MutableList<DomainListener> = mutableListOf(),
    /** The features of this environment, switched on and off through the feature routes and asked by its engine. */
    val featureManager: FeatureManager = FeatureManager()
)