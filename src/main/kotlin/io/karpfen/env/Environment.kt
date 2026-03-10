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

import instance.Model
import meta.Metamodel
import states.StateMachine

data class Environment(
    val key: String,
    var metamodel: Metamodel? = null,
    var model: Model? = null,
    val stateMachines: MutableMap<String, StateMachine> = mutableMapOf(), // modelElement -> attached state machine
    val stateMachineSources: MutableMap<String, String> = mutableMapOf(), // modelElement -> raw kStates DSL text
    var tickDelayMS: Int = 1000,
    /** Time-to-live for events in milliseconds. 0 means events live forever. */
    var eventTtlMs: Long = 0L,
    /** When true (default), events are consumed only when a transition fires. When false, consumed on condition read. */
    var eventConsumptionOnFire: Boolean = true,
    val objectObservations: MutableList<Observation> = mutableListOf(),
    val domainListeners: MutableList<DomainListener> = mutableListOf()
)