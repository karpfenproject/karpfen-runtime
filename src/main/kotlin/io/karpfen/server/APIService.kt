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
package io.karpfen.server

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import dsl.textual.KstatesDSLConverter
import instance.Model
import io.karpfen.env.DomainListener
import io.karpfen.env.EnvironmentHandler
import io.karpfen.env.Observation
import io.karpfen.io.karpfen.exec.StateMachineQueryHelper
import meta.Metamodel

object APIService {

    private fun assertEnv(envKey: String) {
        if (EnvironmentHandler.getEnv(envKey) == null) {
            throw IllegalArgumentException("Environment with key $envKey does not exist")
        }
        if (EnvironmentHandler.isActiveEnv(envKey)) {
            throw IllegalStateException("Environment with key $envKey is already active and cannot be modified in the intended way.")
        }
    }

    private fun assertActiveEnv(envKey: String): Boolean {
        if (EnvironmentHandler.getEnv(envKey) == null) {
            throw IllegalArgumentException("Environment with key $envKey does not exist")
        }
        return EnvironmentHandler.isActiveEnv(envKey)
    }

    fun createEnvironment(): String {
        val envKey = "env-${System.currentTimeMillis()}"
        EnvironmentHandler.createEnv(envKey)
        return envKey
    }

    fun receiveMetamodel(envKey: String, metamodelData: String) {
        assertEnv(envKey)
        val metamodel: Metamodel = KmetaDSLConverter.parseKmetaString(metamodelData)
        EnvironmentHandler.getEnv(envKey)!!.metamodel = metamodel
    }

    fun receiveModel(envKey: String, modelData: String) {
        assertEnv(envKey)
        val metamodel = EnvironmentHandler.getEnv(envKey)!!.metamodel
            ?: throw IllegalStateException("Metamodel must be set before receiving a model for environment $envKey")
        val model: Model = KmodelDSLConverter.parseKmodelString(modelData, metamodel)
        EnvironmentHandler.getEnv(envKey)!!.model = model
    }

    fun receiveEventDefinitions(envKey: String, eventDefinitionData: String) {
        assertEnv(envKey)
        val env = EnvironmentHandler.getEnv(envKey)!!
        // Event payloads may embed or link domain types, so the domain metamodel's types (when present)
        // are made available to the event metamodel as resolvable base types.
        val domainTypes = env.metamodel?.types ?: emptyList()
        val eventMetamodel: Metamodel = KmetaDSLConverter.parseKmetaString(eventDefinitionData, domainTypes)
        env.eventMetamodel = eventMetamodel
        env.eventDefinitionsSource = eventDefinitionData
    }

    fun receiveStateMachine(envKey: String, modelElement: String, stateMachineData: String) {
        assertEnv(envKey)
        val stateMachine = KstatesDSLConverter.parseKstatesString(stateMachineData)
        val env = EnvironmentHandler.getEnv(envKey)!!
        env.stateMachines[modelElement] = stateMachine
        env.stateMachineSources[modelElement] = stateMachineData
    }

    fun updateTickDelay(envKey: String, tickDelayMS: Int) {
        assertEnv(envKey)
        EnvironmentHandler.getEnv(envKey)!!.tickDelayMS = tickDelayMS
    }

    fun updateEventTtl(envKey: String, ttlMs: Long) {
        assertEnv(envKey)
        EnvironmentHandler.getEnv(envKey)!!.eventTtlMs = ttlMs
    }

    /**
     * Forces the state machine [modelElement] of a RUNNING environment into the leaf [state] and clears
     * its pending events (a manual "resync"). Unlike most setters this requires the env to be active.
     */
    fun forceActiveState(envKey: String, modelElement: String, state: String) {
        val env = EnvironmentHandler.getEnv(envKey)
            ?: throw IllegalArgumentException("Environment with key $envKey does not exist")
        if (!EnvironmentHandler.isActiveEnv(envKey)) {
            throw IllegalStateException("Environment with key $envKey is not active")
        }
        val sm = env.stateMachines[modelElement]
            ?: throw IllegalArgumentException("No state machine for model element '$modelElement' in environment $envKey")
        // Validate the leaf up front so an unknown state returns 400 synchronously (the engine-thread
        // command would otherwise only log it).
        if (StateMachineQueryHelper(sm).getStateStackForState(state).isEmpty()) {
            throw IllegalArgumentException("Unknown state '$state' for model element '$modelElement'")
        }
        val envThread = EnvironmentHandler.executionThreads[envKey]
            ?: throw IllegalStateException("Environment with key $envKey has no execution thread")
        envThread.forceActiveState(modelElement, state)
    }

    fun addObjectObservation(envKey: String, clientId: String, objectId: String) {
        assertEnv(envKey)
        EnvironmentHandler.getEnv(envKey)!!.objectObservations.add(Observation(clientId, objectId))
    }

    fun addDomainListener(envKey: String, clientId: String, domain: String) {
        assertEnv(envKey)
        EnvironmentHandler.getEnv(envKey)!!.domainListeners.add(DomainListener(clientId, domain))
    }

    fun runEnvironment(envKey: String) {
        val env = EnvironmentHandler.getEnv(envKey) ?: throw IllegalArgumentException("Environment with key $envKey does not exist")
        if (env.model == null || env.metamodel == null) {
            throw IllegalStateException("Environment with key $envKey must have both a metamodel and a model before it can be run")
        }
        EnvironmentHandler.activeEnv(envKey)
    }

    fun startEnvironment(envKey: String) {
        assertActiveEnv(envKey)
        EnvironmentHandler.startEnvironmentThread(envKey)
    }

    fun stopEnvironment(envKey: String) {
        assertActiveEnv(envKey)
        EnvironmentHandler.stopEnvironmentThread(envKey)
    }

    fun registerClientForWebSocket(clientId: String, envKey: String): String {
        if (EnvironmentHandler.getEnv(envKey) == null) {
            throw IllegalArgumentException("Environment with key $envKey does not exist")
        }
        val accessKey = EnvironmentHandler.registerClientSession(clientId, envKey)
        println("[APIService] Client $clientId registered for environment $envKey with accessKey $accessKey")
        return accessKey
    }

    fun listActiveEnvironments(): List<Map<String, Any>> {
        return EnvironmentHandler.activeEnvs.map { (key, env) ->
            mapOf(
                "envKey" to key,
                "modelElements" to env.stateMachines.keys.toList()
            )
        }
    }

    fun getStateMachineSource(envKey: String, modelElement: String): String {
        val env = EnvironmentHandler.getEnv(envKey)
            ?: throw IllegalArgumentException("Environment with key $envKey does not exist")
        return env.stateMachineSources[modelElement]
            ?: throw IllegalArgumentException("No state machine source found for model element '$modelElement' in environment $envKey")
    }

    fun registerObservatoryClient(clientId: String, envKey: String, modelElement: String): String {
        if (EnvironmentHandler.getEnv(envKey) == null) {
            throw IllegalArgumentException("Environment with key $envKey does not exist")
        }
        val accessKey = EnvironmentHandler.registerClientSession(clientId, envKey)
        EnvironmentHandler.clientSessionManager.subscribeToObservatory(envKey, clientId, modelElement)
        println("[APIService] Observatory client $clientId registered for environment $envKey, element $modelElement with accessKey $accessKey")
        return accessKey
    }

}