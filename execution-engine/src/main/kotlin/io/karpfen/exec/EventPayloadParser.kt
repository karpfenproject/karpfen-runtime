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
package io.karpfen.io.karpfen.exec

import dsl.textual.JsonToModelConverter
import dsl.textual.KmodelDSLConverter
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.PayloadFormat
import meta.Metamodel

/**
 * Turns the raw payload string of an incoming [Event] into a runtime object ([Event.payloadObject]).
 *
 * The event's [Event.name] doubles as the payload's metamodel type, so an event named `setSpeed` is
 * parsed against the `setSpeed` type in the environment's event metamodel. JSON and kmodel payloads
 * both converge on the same kind of object, so everything downstream is format-agnostic.
 *
 * Parsing is best-effort: if there is no event metamodel, no payload, or the payload does not match
 * its declared type, the event simply ends up with a null payload object. It can still trigger
 * transitions by name; only payload access (`$(event->...)`) will then find nothing.
 *
 * @property eventMetamodel The environment's event metamodel, or null when no payloads are defined.
 */
class EventPayloadParser(private val eventMetamodel: Metamodel?) {

    /** Parses [event]'s payload and stores the result on [Event.payloadObject]. */
    fun parseInto(event: Event) {
        val metamodel = eventMetamodel ?: return

        val format = if (event.payloadFormat == PayloadFormat.NONE) {
            PayloadFormat.detect(event.payload)
        } else {
            event.payloadFormat
        }

        event.payloadObject = try {
            when (format) {
                PayloadFormat.NONE -> null
                PayloadFormat.JSON -> JsonToModelConverter(metamodel).toDataObject(event.payload, event.name)
                PayloadFormat.KMODEL -> KmodelDSLConverter.parseSingleObject(event.payload, metamodel)
            }
        } catch (e: Exception) {
            System.err.println(
                "[EventPayloadParser] Could not parse $format payload for event '${event.name}': ${e.message}"
            )
            null
        }
    }
}
