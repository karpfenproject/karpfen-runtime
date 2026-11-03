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

import io.karpfen.io.karpfen.exec.ModelJsonWriter
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
     * Notifies the clients interested in a data change of [objectId]: every client whose object subscriptions contain
     * exactly the id. Each recipient gets one `objectChanged` message.
     *
     * The payload has the members `objectId`, `revision` and `value` in this order. The object id is quoted with
     * [ModelJsonWriter.quote]; [newValue] is the object's JSON and is spliced in as it is. Without a [revision] the
     * member is left out and the payload is written exactly as before revisions existed.
     *
     * @param revision the revision the environment assigned to this change, or `null` for a change without one.
     */
    fun notifyObjectChange(environmentKey: String, objectId: String, newValue: String, revision: Long? = null) {
        val payload = if (revision == null) {
            """{"objectId":${ModelJsonWriter.quote(objectId)},"value":$newValue}"""
        } else {
            """{"objectId":${ModelJsonWriter.quote(objectId)},"revision":$revision,"value":$newValue}"""
        }
        subscriptionsLock.readLock().lock()
        try {
            enqueue(environmentKey, objectRecipients(environmentKey, objectId), "objectChanged", payload)
        } finally {
            subscriptionsLock.readLock().unlock()
        }
    }

    /**
     * Notifies the clients interested in [objectId] that it has been removed from the model, the same recipients as
     * for a change. Each recipient gets one `objectDeleted` message.
     *
     * The payload has the members `objectId` and `revision` in this order; the object id is quoted with
     * [ModelJsonWriter.quote]. Without a [revision] the member is left out, as before revisions existed.
     *
     * @param revision the revision the environment assigned to this deletion, or `null` for one without.
     */
    fun notifyObjectDeleted(environmentKey: String, objectId: String, revision: Long? = null) {
        val payload = if (revision == null) {
            """{"objectId":${ModelJsonWriter.quote(objectId)}}"""
        } else {
            """{"objectId":${ModelJsonWriter.quote(objectId)},"revision":$revision}"""
        }
        subscriptionsLock.readLock().lock()
        try {
            enqueue(environmentKey, objectRecipients(environmentKey, objectId), "objectDeleted", payload)
        } finally {
            subscriptionsLock.readLock().unlock()
        }
    }

    /**
     * Notifies all clients subscribed to a specific domain of an event. The domain is quoted with
     * [ModelJsonWriter.quote]; [payload] is already JSON and is spliced in as it is.
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
                        """{"domain":${ModelJsonWriter.quote(domain)},"payload":$payload}"""
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
                // "*" subscribes to every model element of the environment (the observatory
                // dashboard observes all charts over a single connection and routes each trace
                // by its modelElementId); otherwise deliver only the exactly-subscribed element.
                if (subscribedElement == "*" || subscribedElement == modelElementId) {
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

    /**
     * Notifies the clients interested in the active stack of the machine attached to [modelElementId]: the
     * observatory subscribers of `*` or of exactly that element. Each recipient gets one `stateChange` message with
     * [payload], so a client is never notified twice about one stack change. [notifyObservatory] stays for other
     * callers.
     */
    fun notifyActiveStack(environmentKey: String, modelElementId: String, payload: String) {
        subscriptionsLock.readLock().lock()
        try {
            enqueue(environmentKey, stackRecipients(environmentKey, modelElementId), "stateChange", payload)
        } finally {
            subscriptionsLock.readLock().unlock()
        }
    }

    /**
     * The clients to notify about a change or deletion of [objectId], each once and in subscription order: those whose
     * object subscriptions contain exactly the id. Called with the subscriptions read lock held.
     */
    private fun objectRecipients(environmentKey: String, objectId: String): Set<String> {
        val recipients = LinkedHashSet<String>()
        objectSubscriptions[environmentKey]?.forEach { (clientId, objectIds) ->
            if (objectIds.contains(objectId)) recipients.add(clientId)
        }
        return recipients
    }

    /**
     * The clients to notify about a stack change of the machine attached to [modelElementId], each once: the
     * observatory subscribers of `*` or of exactly that element. Called with the subscriptions read lock held.
     */
    private fun stackRecipients(environmentKey: String, modelElementId: String): Set<String> {
        val recipients = LinkedHashSet<String>()
        observatorySubscriptions[environmentKey]?.forEach { (clientId, subscribedElement) ->
            if (subscribedElement == "*" || subscribedElement == modelElementId) recipients.add(clientId)
        }
        return recipients
    }

    /** Enqueues one message of [messageType] with [payload] for every client in [recipients]. */
    private fun enqueue(environmentKey: String, recipients: Set<String>, messageType: String, payload: String) {
        for (clientId in recipients) {
            outgoingMessageQueue.offer(OutgoingMessage(environmentKey, clientId, messageType, payload))
        }
    }

    private fun generateAccessKey(clientId: String, environmentKey: String): String {
        return "ak-$clientId-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
    }
}
