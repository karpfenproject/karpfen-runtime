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
package io.karpfen.websocket

import io.ktor.websocket.WebSocketSession
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Manages WebSocket connections, client sessions, and message broadcasting.
 * Thread-safe implementation for handling multiple clients and async message delivery.
 */
class ClientSessionManager {
    // Map of environmentKey -> Map of accessKey -> ClientSession
    private val clientSessions = mutableMapOf<String, MutableMap<String, ClientSession>>()
    private val sessionsLock = ReentrantReadWriteLock()

    // Map of environmentKey -> clientId -> list of subscribed objectIds
    private val objectSubscriptions = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
    private val subscriptionsLock = ReentrantReadWriteLock()

    // Map of environmentKey -> clientId -> list of subscribed domains
    private val domainSubscriptions = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()

    // Map of environmentKey -> clientId -> observed modelElementId
    private val observatorySubscriptions = mutableMapOf<String, MutableMap<String, String>>()

    // Queue for outgoing messages to be broadcasted asynchronously
    private val outgoingMessageQueue = LinkedBlockingQueue<OutgoingMessage>()

    // Map of environmentKey -> accessKey -> clientId (registered via HTTP API)
    private val pendingRegistrations = mutableMapOf<String, MutableMap<String, String>>()

    /**
     * Unregisters a client session.
     */
    fun unregisterClientSession(environmentKey: String, accessKey: String) {
        sessionsLock.writeLock().lock()
        try {
            clientSessions[environmentKey]?.remove(accessKey)?.let { removed ->
                println("[ClientSessionManager] Unregistered client ${removed.clientId} from environment $environmentKey")
            }
        } finally {
            sessionsLock.writeLock().unlock()
        }
    }

    /**
     * Retrieves a client session by access key.
     */
    fun getClientSession(environmentKey: String, accessKey: String): ClientSession? {
        sessionsLock.readLock().lock()
        try {
            return clientSessions[environmentKey]?.get(accessKey)
        } finally {
            sessionsLock.readLock().unlock()
        }
    }

    /**
     * Registers a client access key issued by HTTP API.
     */
    fun registerClientAccess(clientId: String, environmentKey: String): String {
        val accessKey = generateAccessKey(clientId, environmentKey)

        sessionsLock.writeLock().lock()
        try {
            val envRegistrations = pendingRegistrations.getOrPut(environmentKey) { mutableMapOf() }
            envRegistrations[accessKey] = clientId
        } finally {
            sessionsLock.writeLock().unlock()
        }

        // Initialize subscription maps for this client (only place this happens)
        subscriptionsLock.writeLock().lock()
        try {
            objectSubscriptions.getOrPut(environmentKey) { mutableMapOf() }
                .getOrPut(clientId) { mutableSetOf() }
            domainSubscriptions.getOrPut(environmentKey) { mutableMapOf() }
                .getOrPut(clientId) { mutableSetOf() }
        } finally {
            subscriptionsLock.writeLock().unlock()
        }

        return accessKey
    }

    /**
     * Verifies the access key and attaches the WebSocket session to the client.
     */
    fun verifyAndAttachSession(
        clientId: String,
        environmentKey: String,
        accessKey: String,
        session: WebSocketSession
    ): Boolean {
        sessionsLock.writeLock().lock()
        try {
            val expectedClientId = pendingRegistrations[environmentKey]?.get(accessKey) ?: return false
            if (expectedClientId != clientId) {
                return false
            }

            val envSessions = clientSessions.getOrPut(environmentKey) { mutableMapOf() }
            envSessions[accessKey] = ClientSession(clientId, environmentKey, accessKey, session)
            pendingRegistrations[environmentKey]?.remove(accessKey)
            if (pendingRegistrations[environmentKey]?.isEmpty() == true) {
                pendingRegistrations.remove(environmentKey)
            }
            return true
        } finally {
            sessionsLock.writeLock().unlock()
        }
    }

    /**
     * Retrieves the session for a client by client ID.
     */
    fun getSessionForClient(environmentKey: String, clientId: String): ClientSession? {
        sessionsLock.readLock().lock()
        try {
            return clientSessions[environmentKey]?.values?.firstOrNull { it.clientId == clientId }
        } finally {
            sessionsLock.readLock().unlock()
        }
    }

    /**
     * Subscribes a client to object changes.
     */
    fun subscribeToObject(environmentKey: String, clientId: String, objectId: String) {
        subscriptionsLock.writeLock().lock()
        try {
            objectSubscriptions.getOrPut(environmentKey) { mutableMapOf() }
                .getOrPut(clientId) { mutableSetOf() }
                .add(objectId)
        } finally {
            subscriptionsLock.writeLock().unlock()
        }
    }

    /**
     * Subscribes a client to domain events.
     */
    fun subscribeToDomain(environmentKey: String, clientId: String, domain: String) {
        subscriptionsLock.writeLock().lock()
        try {
            domainSubscriptions.getOrPut(environmentKey) { mutableMapOf() }
                .getOrPut(clientId) { mutableSetOf() }
                .add(domain)
        } finally {
            subscriptionsLock.writeLock().unlock()
        }
    }

    /**
     * Notifies all clients subscribed to a specific object of a data change.
     */
    fun notifyObjectChange(environmentKey: String, objectId: String, newValue: String) {
        subscriptionsLock.readLock().lock()
        try {
            objectSubscriptions[environmentKey]?.forEach { (clientId, objectIds) ->
                if (objectIds.contains(objectId)) {
                    val message = OutgoingMessage(
                        environmentKey,
                        clientId,
                        "objectChanged",
                        """{"objectId":"$objectId","value":$newValue}"""
                    )
                    outgoingMessageQueue.offer(message)
                }
            }
        } finally {
            subscriptionsLock.readLock().unlock()
        }
    }

    /**
     * Notifies all clients subscribed to a specific domain of an event.
     */
    fun notifyDomainEvent(environmentKey: String, domain: String, payload: String) {
        subscriptionsLock.readLock().lock()
        try {
            domainSubscriptions[environmentKey]?.forEach { (clientId, domains) ->
                if (domains.contains(domain)) {
                    val message = OutgoingMessage(
                        environmentKey,
                        clientId,
                        "domainEvent",
                        """{"domain":"$domain","payload":$payload}"""
                    )
                    outgoingMessageQueue.offer(message)
                }
            }
        } finally {
            subscriptionsLock.readLock().unlock()
        }
    }

    /**
     * Gets the next outgoing message to be sent to clients.
     */
    fun nextOutgoingMessage(timeoutMs: Long = 1000): OutgoingMessage? {
        return outgoingMessageQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Closes all sessions for an environment (e.g., when environment stops).
     */
    fun closeEnvironmentSessions(environmentKey: String) {
        sessionsLock.writeLock().lock()
        try {
            clientSessions[environmentKey]?.values?.forEach { session ->
                try {
                    // Session close will be handled by Ktor
                    println("[ClientSessionManager] Closed session for client ${session.clientId}")
                } catch (e: Exception) {
                    println("[ClientSessionManager] Error closing session: ${e.message}")
                }
            }
            clientSessions.remove(environmentKey)
        } finally {
            sessionsLock.writeLock().unlock()
        }
    }

    /**
     * Subscribes a client to observatory trace and state updates for an environment.
     * @param the model element id is the id of an object most likely a statemachine is attached to.
     *  Although other objects might be observed, they produce no productive traces.
     */
    fun subscribeToObservatory(environmentKey: String, clientId: String, modelElementId: String) {
        subscriptionsLock.writeLock().lock()
        try {
            observatorySubscriptions.getOrPut(environmentKey) { mutableMapOf() }[clientId] = modelElementId
        } finally {
            subscriptionsLock.writeLock().unlock()
        }
    }

    /**
     * Unsubscribes a client from observatory trace and state updates for an environment.
     */
    fun unsubscribeFromObservatory(environmentKey: String, clientId: String) {
        subscriptionsLock.writeLock().lock()
        try {
            observatorySubscriptions[environmentKey]?.remove(clientId)
            if (observatorySubscriptions[environmentKey]?.isEmpty() == true) {
                observatorySubscriptions.remove(environmentKey)
            }
        } finally {
            subscriptionsLock.writeLock().unlock()
        }
    }

    /**
     * Notifies all observatory-subscribed clients of a trace or state update.
     */
    fun notifyObservatory(environmentKey: String, modelElementId: String, messageType: String, payload: String) {
        subscriptionsLock.readLock().lock()
        try {
            observatorySubscriptions[environmentKey]?.forEach { (clientId, subscribedElement) ->
                if (subscribedElement == modelElementId) {
                    val message = OutgoingMessage(
                        environmentKey,
                        clientId,
                        messageType,
                        payload
                    )
                    outgoingMessageQueue.offer(message)
                }
            }
        } finally {
            subscriptionsLock.readLock().unlock()
        }
    }

    private fun generateAccessKey(clientId: String, environmentKey: String): String {
        return "ak-$clientId-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
    }
}
