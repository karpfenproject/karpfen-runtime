package io.karpfen.io.karpfen.features.runtime.event

import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.PayloadFormat
import org.json.JSONObject

//Serialize only necessary data
fun parseStringFromEvent(event: Event): String {
    val json = JSONObject()
    json.put("environmentKey", event.domain)
    json.put("messageType", event.name)
    json.put("payload", event.payload)
    json.put("payloadFormat", event.payloadFormat)
    return json.toString()
}

fun parseEventFromString(string: String): Event {
    val json = JSONObject(string)
    val domain = json.getString("environmentKey")
    val name = json.getString("messageType")
    val payload = json.getString("payload")
    var format: PayloadFormat? = null
    if (json.has("payloadFormat")) {
        format = PayloadFormat.fromString(json.getString("payloadFormat"))
    }
    if (format == null) {
        format = PayloadFormat.detect(payload)
    }
    return Event(domain, name, payload, System.currentTimeMillis(), payloadFormat = format)
}
