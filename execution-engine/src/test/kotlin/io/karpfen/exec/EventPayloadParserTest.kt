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
package io.karpfen.exec

import io.karpfen.Engine
import io.karpfen.dsl.dsl.textual.KmetaDSLConverter
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.io.karpfen.exec.EventPayloadParser
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.messages.PayloadFormat
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers how [EventPayloadParser] treats kmodel payloads: a payload within the parser's reach becomes the event's
 * payload object, while one nested too deeply to parse and one with an error of the KModel reader leave the event
 * without one instead of ending the thread that parses it. An engine still fires a transition on an event whose
 * payload could not be read and goes on ticking.
 */
class EventPayloadParserTest {

    /** A parser over an event metamodel whose one event type nests itself. */
    private val parser = EventPayloadParser(KmetaDSLConverter.parseKmetaString(NESTING_EVENTS))

    @Test
    fun `a nested payload within reach becomes the event's payload object`() {
        val event = nestedEvent(depth = 3)

        parser.parseInto(event)

        val payload = assertNotNull(event.payloadObject)
        assertEquals("level-0", payload.id)
        assertEquals("level-1", payload.getRel("inner").single().id)
    }

    @Test
    fun `a payload nested too deeply to parse leaves the event without a payload object`() {
        val event = nestedEvent(depth = TOO_DEEP)
        val escaped = AtomicReference<Throwable?>()
        val parsing = Thread(null, {
            try {
                parser.parseInto(event)
            } catch (failure: Throwable) {
                escaped.set(failure)
            }
        }, "payload-parser", SMALL_STACK_BYTES)

        parsing.start()
        parsing.join()

        assertNull(escaped.get(), "Nothing may escape the parser")
        assertNull(event.payloadObject)
    }

    @Test
    fun `a payload with an error of the reader leaves the event without a payload object`() {
        val unknownProperty = Event("public", "nest", """make object "a":"nest" { prop("depth") -> "1" }""",
            payloadFormat = PayloadFormat.KMODEL)
        val syntaxError = Event("public", "nest", """make object "a":"nest" { has("inner") -> }""",
            payloadFormat = PayloadFormat.KMODEL)

        parser.parseInto(unknownProperty)
        parser.parseInto(syntaxError)

        assertNull(unknownProperty.payloadObject)
        assertNull(syntaxError.payloadObject)
    }

    @Test
    fun `an engine fires a transition on an event whose payload could not be read and goes on ticking`() {
        val metamodel = KmetaReader().read(THING).model!!
        val engine = Engine(
            metamodel = metamodel,
            model = KmodelReader(metamodel).read("""make object "t":"Thing" { }""").model!!,
            statemachineMap = mapOf("t" to KstatesReader().read(WAITING).model!!),
            tickDelayMS = 1,
            eventBus = EventBus(),
            eventMetamodel = KmetaReader().read(NESTING_EVENTS).model!!
        )
        val stacks = CopyOnWriteArrayList<List<String>>()
        engine.onActiveStackChanged = { _, stack -> stacks += stack }
        try {
            engine.start()
            await { stacks.lastOrNull() == listOf("waiting") }
            val event = Event("t", "nest", """make object "a":"nest" { prop("depth") -> "1" }""",
                payloadFormat = PayloadFormat.KMODEL)
            engine.receiveExternalEvent(event)
            await { stacks.lastOrNull() == listOf("done") }

            assertNull(event.payloadObject)
            assertTrue(engine.isTicking)
        } finally {
            engine.close()
        }
    }

    /** Waits up to 10 s for [reached]. */
    private fun await(reached: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!reached()) {
            if (System.currentTimeMillis() > deadline) fail("The engine did not get there in time")
            Thread.sleep(2)
        }
    }

    /** An event of the nesting type whose kmodel payload nests [depth] objects, each inside the one before. */
    private fun nestedEvent(depth: Int): Event {
        val payload = StringBuilder()
        repeat(depth) { level ->
            if (level > 0) payload.append("has(\"inner\") -> ")
            payload.append("make object \"level-").append(level).append("\":\"nest\" { ")
        }
        repeat(depth) { payload.append("} ") }
        return Event("public", "nest", payload.toString(), payloadFormat = PayloadFormat.KMODEL)
    }

    private companion object {
        /** An event metamodel with the one event type `nest`, which may hold another `nest`. */
        val NESTING_EVENTS = """
            type "nest" "an event whose payload nests itself" {
                has("inner", "nest")
            }
        """.trimIndent()

        /** A metamodel with one type for the object of the state machine. */
        val THING = """type "Thing" "a thing" { prop("log", list("string")) }"""

        /** Waits in `waiting` for the event `nest` of the domain `t`. */
        val WAITING = """
            STATEMACHINE ATTACHED TO "Thing" {
                STATES {
                    INITIAL STATE "waiting" { }
                    STATE "done" { }
                }
                TRANSITIONS {
                    TRANSITION "waiting" -> "done" { CONDITION { EVENT("t", "nest") } }
                }
            }
        """.trimIndent()

        /** A nesting far deeper than the parser's recursion reaches on [SMALL_STACK_BYTES]. */
        const val TOO_DEEP = 20_000

        /** The stack of the parsing thread: small, so that [TOO_DEEP] overflows it on every platform. */
        const val SMALL_STACK_BYTES = 256L * 1024L
    }
}
