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

import io.karpfen.io.karpfen.exec.ModelChangePublisher
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventPublicationListener
import io.karpfen.io.karpfen.messages.PayloadFormat
import io.karpfen.websocket.ClientSessionManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns what the engine of one environment observes into push messages for its WebSocket clients.
 *
 * The relay receives every object change and deletion as the environment's [ModelChangePublisher] and every
 * active-stack change through [onActiveStackChanged]. All of them arrive on the engine thread inside a tick; the relay
 * assigns each one the next [revision] and hands it to the [sessions], which enqueue one message per recipient in the
 * same call. Every recipient of one observation therefore gets the same revision, and the revisions of an
 * environment increase in enqueue order. A change whose object cannot be written uses up its revision without a
 * message, so that the gap reveals it.
 *
 * As the [EventPublicationListener] of the environment's event bus, the relay also announces every published event to
 * the domain listeners of its domain. Domain events carry no revision: they are not model state, and external events
 * are published on the environment thread outside any tick, so they cannot be ordered against a snapshot.
 *
 * @property environmentKey the environment whose engine this relay listens to.
 * @property sessions the session manager that selects the recipients and enqueues the messages.
 * @property revision the environment's observation counter.
 * @property activeStacks the environment's record of the last reported stacks, read by snapshots.
 */
class ObservationRelay(
    private val environmentKey: String,
    private val sessions: ClientSessionManager,
    private val revision: ChangeRevision,
    private val activeStacks: ActiveStackRecord
) : ModelChangePublisher, EventPublicationListener {

    /** Pushes `objectChanged` for [objectId] with its JSON as the engine committed it, under the next revision. */
    override fun onObjectChanged(objectId: String, jsonValue: String) {
        sessions.notifyObjectChange(environmentKey, objectId, jsonValue, revision.next())
    }

    /** Pushes `objectDeleted` for [objectId] under the next revision. */
    override fun onObjectDeleted(objectId: String) {
        sessions.notifyObjectDeleted(environmentKey, objectId, revision.next())
    }

    /**
     * Uses up the next revision for a change of [objectId] that has no JSON, because an object is reachable from itself
     * through its relations, and pushes nothing. A model subscriber therefore sees a gap in the revisions and takes a
     * snapshot, which is refused while the loop exists, instead of keeping a value that silently went stale.
     */
    override fun onObjectUnwritable(objectId: String) {
        revision.next()
    }

    /**
     * Records the new active [stack] of the machine attached to [modelElementId] and pushes `stateChange` under the
     * next revision. The payload is an org.json object with `modelElementId`, `stack` and `revision`.
     */
    fun onActiveStackChanged(modelElementId: String, stack: List<String>) {
        activeStacks.record(modelElementId, stack)
        val payload = JSONObject()
            .put("modelElementId", modelElementId)
            .put("stack", JSONArray(stack))
            .put("revision", revision.next())
            .toString()
        sessions.notifyActiveStack(environmentKey, modelElementId, payload)
    }

    /**
     * Pushes `domainEvent` for a published [event] to the listeners of its domain. The inner payload is an org.json
     * object with the event name as `event`, the raw payload text as `payload` (never spliced as JSON), the
     * `payloadFormat` (`none`, `json` or `kmodel`), the `source` (the engine's event source name), the receipt or raise
     * `timestamp` in epoch milliseconds and the effective `ttlMs`.
     */
    override fun onPublished(event: Event) {
        val payload = JSONObject()
            .put("event", event.name)
            .put("payload", event.payload)
            .put("payloadFormat", formatName(event.payloadFormat))
            .put("source", event.source.name)
            .put("timestamp", event.timestamp)
            .put("ttlMs", event.ttlMs)
            .toString()
        sessions.notifyDomainEvent(environmentKey, event.domain, payload)
    }

    /** The wire name of a payload format, as clients name it in their event frames. */
    private fun formatName(format: PayloadFormat): String = when (format) {
        PayloadFormat.NONE -> "none"
        PayloadFormat.JSON -> "json"
        PayloadFormat.KMODEL -> "kmodel"
    }
}
