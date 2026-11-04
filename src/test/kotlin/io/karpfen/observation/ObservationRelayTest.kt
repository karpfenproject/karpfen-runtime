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

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.messages.EventSource
import io.karpfen.io.karpfen.messages.PayloadFormat
import io.karpfen.websocket.ClientSessionManager
import io.karpfen.websocket.OutgoingMessage
import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [ObservationRelay] with a fresh [ClientSessionManager] and no engine: the revisions of object, deletion and
 * stack pushes, the revision gap of a change that cannot be written, their recipients, the record of active stacks,
 * domain events, and the escaping of pushed strings.
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
    fun `a change that cannot be written uses up a revision without a message`() {
        sessions.subscribeToModel(ENV, "ide")

        relay.onObjectChanged("turtle", TURTLE_JSON)
        relay.onObjectUnwritable("ring")
        relay.onObjectDeleted("gone")

        assertEquals(
            listOf("objectChanged" to 1L, "objectDeleted" to 3L),
            drain().map { it.messageType to JSONObject(it.payload).getLong("revision") }
        )
        assertEquals(3L, revisions.current())
    }

    @Test
    fun `a block reaching a reference loop pushes its other changes and deletions behind a revision gap`() {
        val metamodel = KmetaDSLConverter.parseKmetaString(CHAIN_METAMODEL)
        val model = ModelQueryProcessor(metamodel, KmodelDSLConverter.parseKmodelString(CHAIN_MODEL, metamodel))
        model.addChangePublisher(relay)
        sessions.subscribeToModel(ENV, "ide")

        model.beginBatch()
        model.setObjectRelation("b", "next", model.getDataObjectById("a"))
        model.setScalar("d", "v", 9.0)
        model.dropObject("c")
        assertFailsWith<IllegalStateException> { model.commitBatch() }

        assertEquals(
            listOf(Triple("objectChanged", "d", 2L), Triple("objectDeleted", "c", 4L)),
            drain().map {
                val payload = JSONObject(it.payload)
                Triple(it.messageType, payload.getString("objectId"), payload.getLong("revision"))
            }
        )
        assertEquals(4L, revisions.current())
    }

    @Test
    fun `a client subscribed to the model and to an object gets one message per change`() {
        sessions.subscribeToModel(ENV, "ide")
        sessions.subscribeToObject(ENV, "ide", "turtle")

        relay.onObjectChanged("turtle", TURTLE_JSON)
        relay.onObjectDeleted("turtle")

        val messages = drain()
        assertEquals(
            listOf("ide" to "objectChanged", "ide" to "objectDeleted"),
            messages.map { it.clientId to it.messageType }
        )
    }

    @Test
    fun `stack changes reach model subscribers and observatory subscribers once each`() {
        sessions.subscribeToModel(ENV, "both")
        sessions.subscribeToObservatory(ENV, "both", "*")
        sessions.subscribeToModel(ENV, "model")
        sessions.subscribeToObservatory(ENV, "watcher", "turtle")

        relay.onActiveStackChanged("turtle", listOf("observe"))
        relay.onActiveStackChanged("lamp", listOf("off"))

        val messages = drain()
        assertEquals(
            listOf("both" to "turtle", "watcher" to "turtle", "model" to "turtle", "both" to "lamp", "model" to "lamp"),
            messages.map { it.clientId to JSONObject(it.payload).getString("modelElementId") }
        )
        assertEquals(listOf(1L, 1L, 1L, 2L, 2L), messages.map { JSONObject(it.payload).getLong("revision") })
        assertTrue(messages.all { it.messageType == "stateChange" })
    }

    @Test
    fun `objects unknown at subscription time reach model subscribers`() {
        sessions.subscribeToModel(ENV, "ide")

        relay.onObjectChanged("gen_Vector_0", """{"__id__": "gen_Vector_0", "__type__": "Vector", "x": 1, "y": 0}""")
        relay.onObjectDeleted("gen_Vector_0")

        assertEquals(
            listOf("objectChanged" to "gen_Vector_0", "objectDeleted" to "gen_Vector_0"),
            drain().map { it.messageType to JSONObject(it.payload).getString("objectId") }
        )
    }

    @Test
    fun `unsubscribing stops new object and stack pushes but keeps per-object observations and the observatory registration`() {
        sessions.subscribeToModel(ENV, "ide")
        sessions.subscribeToObject(ENV, "ide", "turtle")
        sessions.subscribeToObservatory(ENV, "ide", "turtle")

        sessions.unsubscribeFromModel(ENV, "ide")
        relay.onObjectChanged("turtle", TURTLE_JSON)
        relay.onObjectChanged("turtleDirection", TURTLE_JSON)
        relay.onObjectDeleted("turtleDirection")
        relay.onActiveStackChanged("turtle", listOf("observe"))
        relay.onActiveStackChanged("lamp", listOf("off"))

        assertFalse(sessions.isSubscribedToModel(ENV, "ide"))
        assertEquals(
            listOf("objectChanged" to 1L, "stateChange" to 4L),
            drain().map { it.messageType to JSONObject(it.payload).getLong("revision") }
        )
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

    @Test
    fun `published events become domainEvent messages for listeners of their domain`() {
        sessions.subscribeToDomain(ENV, "listener", "public")
        sessions.subscribeToDomain(ENV, "other", "control")
        val bus = EventBus(defaultTtlMs = 3000L)
        bus.addPublicationListener(relay)

        bus.publish(
            Event(
                domain = "public",
                name = "setSpeed",
                payload = """{"factor": 2}""",
                timestamp = 1761560130123L,
                source = EventSource.EXTERNAL_MESSAGE,
                payloadFormat = PayloadFormat.JSON
            )
        )

        val message = drain().single()
        assertEquals(listOf("listener", "domainEvent"), listOf(message.clientId, message.messageType))
        assertTrue(message.payload.startsWith("""{"domain":"public","payload":{"""))
        val payload = JSONObject(message.payload)
        assertEquals(setOf("domain", "payload"), payload.keySet())
        val inner = payload.getJSONObject("payload")
        assertEquals(setOf("event", "payload", "payloadFormat", "source", "timestamp", "ttlMs"), inner.keySet())
        assertEquals("setSpeed", inner.getString("event"))
        assertEquals("""{"factor": 2}""", inner.getString("payload"))
        assertEquals("json", inner.getString("payloadFormat"))
        assertEquals("EXTERNAL_MESSAGE", inner.getString("source"))
        assertEquals(1761560130123L, inner.getLong("timestamp"))
        assertEquals(3000L, inner.getLong("ttlMs"))
        assertEquals(0L, revisions.current())
    }

    @Test
    fun `object ids and domain names are escaped in pushed messages`() {
        val oddId = "odd \"id\" with \\ and \n"
        val oddDomain = "dom\"ain\\"
        val oddClient = "client \"one\""
        sessions.subscribeToObject(ENV, oddClient, oddId)
        sessions.subscribeToDomain(ENV, oddClient, oddDomain)

        relay.onObjectChanged(oddId, TURTLE_JSON)
        relay.onObjectDeleted(oddId)
        relay.onPublished(Event(oddDomain, "start", timestamp = 1761560130123L))

        val messages = drain()
        assertEquals(
            listOf(
                """{"objectId":"odd \"id\" with \\ and \n","revision":1,"value":$TURTLE_JSON}""",
                """{"objectId":"odd \"id\" with \\ and \n","revision":2}"""
            ),
            messages.take(2).map { it.payload }
        )
        assertTrue(messages[2].payload.startsWith("""{"domain":"dom\"ain\\","payload":{"""))
        val envelopes = messages.map { JSONObject(it.toJson()) }
        assertEquals(listOf(oddClient), envelopes.map { it.getString("clientId") }.distinct())
        assertEquals(listOf(oddId, oddId), envelopes.take(2).map { it.getJSONObject("payload").getString("objectId") })
        assertEquals(oddDomain, envelopes[2].getJSONObject("payload").getString("domain"))
    }

    /** Takes every message the relay enqueued so far; the relay enqueues synchronously, so nothing is awaited. */
    private fun drain(): List<OutgoingMessage> = generateSequence { sessions.nextOutgoingMessage(0) }.toList()

    private companion object {
        /** The environment the relay serves. */
        const val ENV = "env-1761560123456"

        /** A changed object's JSON as the engine hands it to the relay. */
        const val TURTLE_JSON = """{"__id__": "turtle", "__type__": "Robot", "d_closest_obstacle": 0.2}"""

        /** Nodes that know the next node, embedded in a holder. */
        val CHAIN_METAMODEL = """
            type "Node" "A node that knows the next node" {
                prop("v", "number")
                knows("next", "Node")
            }
            type "Holder" "Embeds the nodes" {
                has("nodes", list("Node"))
            }
        """.trimIndent()

        /** The holder `h` with the chain `a` to `b` to `c` and the unrelated node `d`. */
        val CHAIN_MODEL = """
            make object "h":"Holder" {
                has("nodes") -> make object "a":"Node" {
                    prop("v") -> "0"
                    knows("next") -> "b"
                }
                has("nodes") -> make object "b":"Node" {
                    prop("v") -> "0"
                    knows("next") -> "c"
                }
                has("nodes") -> make object "c":"Node" {
                    prop("v") -> "0"
                }
                has("nodes") -> make object "d":"Node" {
                    prop("v") -> "0"
                }
            }
        """.trimIndent()
    }
}
