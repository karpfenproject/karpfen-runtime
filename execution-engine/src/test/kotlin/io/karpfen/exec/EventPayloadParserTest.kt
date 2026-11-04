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

import dsl.textual.KmetaDSLConverter
import io.karpfen.io.karpfen.exec.EventPayloadParser
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.PayloadFormat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers how [EventPayloadParser] treats kmodel payloads by their nesting: a payload within the parser's reach becomes
 * the event's payload object, and one nested too deeply to parse leaves the event without one instead of ending the
 * thread that parses it.
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

        /** A nesting far deeper than the parser's recursion reaches on [SMALL_STACK_BYTES]. */
        const val TOO_DEEP = 20_000

        /** The stack of the parsing thread: small, so that [TOO_DEEP] overflows it on every platform. */
        const val SMALL_STACK_BYTES = 256L * 1024L
    }
}
