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

import io.karpfen.dsl.dsl.textual.KmodelDSLConverter
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.env.DomainListener
import io.karpfen.env.EnvironmentHandler
import io.karpfen.env.Observation
import io.karpfen.io.karpfen.exec.StateMachineQueryHelper
import io.karpfen.io.karpfen.features.FeatureRegistry

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

    /**
     * Creates an environment and returns its key `env-<epoch millis>`, which is never handed out twice (see
     * [EnvironmentHandler.createUniqueEnv]).
     */
    fun createEnvironment(): String = EnvironmentHandler.createUniqueEnv().key

    /**
     * Reads [metamodelData] with a `KmetaReader` and stores the metamodel in the environment [envKey].
     *
     * @throws UploadRejectedException if the text has errors; the environment then stays as it was.
     */
    fun receiveMetamodel(envKey: String, metamodelData: String) {
        assertEnv(envKey)
        val metamodel = UploadReading.acceptOrReject(envKey, "metamodel", KmetaReader().read(metamodelData))
        EnvironmentHandler.getEnv(envKey)!!.metamodel = metamodel
    }

    /**
     * Reads [modelData] with a `KmodelReader` against the metamodel of the environment [envKey] and stores the model.
     *
     * @throws IllegalStateException if the environment has no metamodel yet.
     * @throws UploadRejectedException if the text has errors; the environment then stays as it was.
     */
    fun receiveModel(envKey: String, modelData: String) {
        assertEnv(envKey)
        val metamodel = EnvironmentHandler.getEnv(envKey)!!.metamodel
            ?: throw IllegalStateException("Metamodel must be set before receiving a model for environment $envKey")
        val model = UploadReading.acceptOrReject(envKey, "model", KmodelReader(metamodel).read(modelData))
        EnvironmentHandler.getEnv(envKey)!!.model = model
    }

    /**
     * Reads [eventDefinitionData] with a `KmetaReader` and stores the event metamodel and its text in the environment
     * [envKey].
     *
     * Event payloads may embed or link domain types, so the types of the domain metamodel, when there is one, are
     * the base types of the read: the event types may refer to them without defining them.
     *
     * @throws UploadRejectedException if the text has errors; the environment then stays as it was.
     */
    fun receiveEventDefinitions(envKey: String, eventDefinitionData: String) {
        assertEnv(envKey)
        val env = EnvironmentHandler.getEnv(envKey)!!
        val domainTypes = env.metamodel?.types ?: emptyList()
        val eventMetamodel = UploadReading.acceptOrReject(
            envKey, "event definitions", KmetaReader(domainTypes).read(eventDefinitionData)
        )
        env.eventMetamodel = eventMetamodel
        env.eventDefinitionsSource = eventDefinitionData
    }

    /**
     * Reads [stateMachineData] with a `KstatesReader`, every optional language feature switched on, and stores the
     * state machine and its text in the environment [envKey] for the model element [modelElement].
     *
     * @throws UploadRejectedException if the text has errors; the environment then stays as it was.
     */
    fun receiveStateMachine(envKey: String, modelElement: String, stateMachineData: String) {
        assertEnv(envKey)
        val stateMachine = UploadReading.acceptOrReject(
            envKey, "state machine for '$modelElement'", KstatesReader().read(stateMachineData)
        )
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

    /**
     * Activates the environment [envKey] once it has a metamodel and a model.
     *
     * Every state machine must be attached to an object of the model, nested objects included; otherwise the engine
     * thread would die at its start while every call had answered 200. Missing attachments are refused with an
     * [IllegalStateException] naming the keys, comma-separated, and the environment stays configurable. Activation
     * itself happens at most once (see [EnvironmentHandler.activeEnv]).
     */
    fun runEnvironment(envKey: String) {
        val env = EnvironmentHandler.getEnv(envKey) ?: throw IllegalArgumentException("Environment with key $envKey does not exist")
        if (env.model == null || env.metamodel == null) {
            throw IllegalStateException("Environment with key $envKey must have both a metamodel and a model before it can be run")
        }
        val modelIds = KmodelDSLConverter.collectAllObjects(env.model!!.objects).map { it.id }.toSet()
        val missingAttachments = env.stateMachines.keys.filter { it !in modelIds }
        if (missingAttachments.isNotEmpty()) {
            throw IllegalStateException(
                "Environment with key $envKey has state machines attached to objects missing from its model: " +
                    missingAttachments.joinToString(", ")
            )
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

    fun getRegisteredFeatures(): Set<String> {
        return FeatureRegistry.getFeatureNames()
    }

    fun activateFeature(envKey: String, featureName: String) {
        val env = EnvironmentHandler.getEnv(envKey)
            ?: throw IllegalArgumentException("Environment with key $envKey does not exist")
        val featureClass = FeatureRegistry.getClassByName(featureName)
            ?: throw IllegalArgumentException("Feature '$featureName' is not registered")
        if (!env.featureManager.requestFeatureActivation(featureClass))
            throw IllegalArgumentException("Feature '$featureName' is already active in environment $envKey")
    }

    fun getActiveFeatures(envKey: String): Set<String> {
        val env = EnvironmentHandler.getEnv(envKey)
            ?: throw IllegalArgumentException("Environment with key $envKey does not exist")
        val set = mutableSetOf<String>()
        for (featureClass in env.featureManager.getActiveFeatureClasses()) {
            FeatureRegistry.getNameByClass(featureClass)?.let { set.add(it) }
        }
        return set
    }

    fun deactivateFeature(envKey: String, featureName: String) {
        val env = EnvironmentHandler.getEnv(envKey)
            ?: throw IllegalArgumentException("Environment with key $envKey does not exist")
        val featureClass = FeatureRegistry.getClassByName(featureName)
            ?: throw IllegalArgumentException("Feature '$featureName' is not registered")
        if (!env.featureManager.requestFeatureDeactivation(featureClass))
            throw IllegalArgumentException("Feature '$featureName' is not active in environment $envKey")
    }

    fun sendMessage(envKey: String, featureName: String, message: String): String {
        val env = EnvironmentHandler.getEnv(envKey)
            ?: throw IllegalArgumentException("Environment with key $envKey does not exist")
        val featureClass = FeatureRegistry.getClassByName(featureName)
            ?: throw IllegalArgumentException("Feature '$featureName' is not registered")
        return env.featureManager.onMessage(featureClass, message) ?: throw IllegalArgumentException("${FeatureRegistry.getNameByClass(featureClass)} has not been activated in environment $envKey")
    }
}