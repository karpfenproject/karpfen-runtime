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

import io.karpfen.websocket.ClientSessionManager

/**
 * Subscribes WebSocket clients to the whole model of an environment and unsubscribes them, in every phase.
 *
 * Subscribing requires that the client has an attached WebSocket session for the environment. Pushes for a client
 * without a session are dropped, and the WebSocket handshake is never acknowledged, so this check is the
 * synchronization point a client lacks otherwise: once a subscribe succeeded, a session is attached and every later
 * push reaches a socket while it stays open. The subscription itself is keyed by environment and client id, so it
 * survives reconnects.
 *
 * @property environments where environments are found.
 * @property sessions holds the sessions and the subscriptions.
 */
class ModelSubscriptionService(
    private val environments: EnvironmentLookup,
    private val sessions: ClientSessionManager
) {

    /**
     * Subscribes [clientId] to the whole model of [envKey].
     *
     * @throws IllegalArgumentException when the environment does not exist.
     * @throws IllegalStateException when the client has no attached WebSocket session for the environment.
     */
    fun subscribe(envKey: String, clientId: String) {
        requireEnvironment(envKey)
        if (sessions.getSessionForClient(envKey, clientId) == null) {
            throw IllegalStateException("Client $clientId has no open WebSocket session for environment $envKey")
        }
        sessions.subscribeToModel(envKey, clientId)
    }

    /**
     * Ends the model subscription of [clientId] in [envKey]; object observers and observatory registrations stay.
     *
     * @throws IllegalArgumentException when the environment does not exist.
     */
    fun unsubscribe(envKey: String, clientId: String) {
        requireEnvironment(envKey)
        sessions.unsubscribeFromModel(envKey, clientId)
    }

    /** Refuses an unknown environment with the runtime's text for it. */
    private fun requireEnvironment(envKey: String) {
        if (!environments.exists(envKey)) {
            throw IllegalArgumentException("Environment with key $envKey does not exist")
        }
    }
}
