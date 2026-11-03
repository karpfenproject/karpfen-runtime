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
 * environment increase in enqueue order.
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
) : ModelChangePublisher {

    /** Pushes `objectChanged` for [objectId] with its JSON as the engine committed it, under the next revision. */
    override fun onObjectChanged(objectId: String, jsonValue: String) {
        sessions.notifyObjectChange(environmentKey, objectId, jsonValue, revision.next())
    }

    /** Pushes `objectDeleted` for [objectId] under the next revision. */
    override fun onObjectDeleted(objectId: String) {
        sessions.notifyObjectDeleted(environmentKey, objectId, revision.next())
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
}
