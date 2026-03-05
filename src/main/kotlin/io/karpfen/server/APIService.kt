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

    fun receiveStateMachine(envKey: String, modelElement: String, stateMachineData: String) {
        assertEnv(envKey)
        val stateMachine = KstatesDSLConverter.parseKstatesString(stateMachineData)
        EnvironmentHandler.getEnv(envKey)!!.stateMachines[modelElement] = stateMachine
    }

    fun updateTickDelay(envKey: String, tickDelayMS: Int) {
        assertEnv(envKey)
        EnvironmentHandler.getEnv(envKey)!!.tickDelayMS = tickDelayMS
    }

    fun addObjectObservation(envKey: String, clientId: String, objectId: String) {
        assertEnv(envKey)
        EnvironmentHandler.getEnv(envKey)!!.objectObservations.add(Observation(clientId, objectId))
    }

    fun addDomainListener(envKey: String, clientId: String, domain: String) {
        assertEnv(envKey)
        EnvironmentHandler.getEnv(envKey)!!.domainListeners.add(DomainListener(clientId, domain))

    }

}