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
import io.karpfen.websocket.OutgoingMessage
import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Covers [ObservationRelay] with a fresh [ClientSessionManager] and no engine: the revisions of object, deletion and
 * stack pushes, their recipients, and the record of active stacks.
 */
class ObservationRelayTest {

    /** A fresh session manager whose queue the tests drain. */
    private val sessions = ClientSessionManager()

    /** The environment's revision counter. */
    private val revisions = ChangeRevision()

    /** The environment's record of active stacks. */
    private val activeStacks = ActiveStackRecord()

    /** The relay under test. */
    private val relay = ObservationRelay(ENV, sessions, revisions, activeStacks)

    @Test
    fun `object changes deletions and stack changes carry consecutive revisions`() {
        sessions.subscribeToObject(ENV, "observer", "turtle")
        sessions.subscribeToObject(ENV, "observer", "gone")
        sessions.subscribeToObservatory(ENV, "observer", "*")

        relay.onObjectChanged("turtle", TURTLE_JSON)
        relay.onObjectDeleted("gone")
        relay.onActiveStackChanged("turtle", listOf("observe"))
        relay.onObjectChanged("turtle", TURTLE_JSON)

        val messages = drain()
        assertEquals(
            listOf("objectChanged", "objectDeleted", "stateChange", "objectChanged"),
            messages.map { it.messageType }
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), messages.map { JSONObject(it.payload).getLong("revision") })
        assertEquals(4L, revisions.current())
    }

    @Test
    fun `per-object observers receive the revision too`() {
        sessions.subscribeToObject(ENV, "observer", "turtle")
        sessions.subscribeToObject(ENV, "observer", "gone")

        relay.onObjectChanged("turtle", TURTLE_JSON)
        relay.onObjectDeleted("gone")

        assertEquals(
            listOf(
                """{"objectId":"turtle","revision":1,"value":$TURTLE_JSON}""",
                """{"objectId":"gone","revision":2}"""
            ),
            drain().map { it.payload }
        )
    }

    @Test
    fun `active stacks are recorded for snapshots`() {
        relay.onActiveStackChanged("turtle", listOf("observe"))
        relay.onActiveStackChanged("lamp", listOf("off"))
        val beforeLastChange = activeStacks.current()
        relay.onActiveStackChanged("turtle", listOf("observe", "react to obstacle"))

        assertEquals(listOf("turtle", "lamp"), activeStacks.current().keys.toList())
        assertEquals(listOf("observe", "react to obstacle"), activeStacks.current()["turtle"])
        assertEquals(listOf("observe"), beforeLastChange["turtle"])
        assertEquals(3L, revisions.current())
    }

    /** Takes every message the relay enqueued so far; the relay enqueues synchronously, so nothing is awaited. */
    private fun drain(): List<OutgoingMessage> = generateSequence { sessions.nextOutgoingMessage(0) }.toList()

    private companion object {
        /** The environment the relay serves. */
        const val ENV = "env-1761560123456"

        /** A changed object's JSON as the engine hands it to the relay. */
        const val TURTLE_JSON = """{"__id__": "turtle", "__type__": "Robot", "d_closest_obstacle": 0.2}"""
    }
}
