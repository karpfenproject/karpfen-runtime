package io.karpfen.io.karpfen.features.runtime.event

import com.google.auto.service.AutoService
import io.karpfen.io.karpfen.exec.EventProcessor
import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import io.karpfen.io.karpfen.messages.Event
import org.json.JSONObject
import java.util.SortedMap
import kotlin.reflect.KClass

class EventInjectionFeature : DefaultFeature() {

    @Volatile
    private var isInjectingEvents: Boolean = false

    private var eventMap: SortedMap<Long, List<Event>> = sortedMapOf()

    override fun onMessage(message: String): String {
        return when (message) {
            "start" -> {
                isInjectingEvents = true
                "injection started"
            }
            "stop" -> {
                isInjectingEvents = false
                "injection stopped"
            }
            "clear" -> {
                eventMap.clear()
                "cleared event queue"
            }
            "get" -> {
                JSONObject(eventMap.mapValues { (_, eventList) -> eventList.map { parseStringFromEvent(it) } }).toString()
            }
            else -> {
                if (message.startsWith("upload:")) {
                    val payload = JSONObject(message.removePrefix("upload:"))
                    val newMap = sortedMapOf<Long, List<Event>>()
                    payload.keys().forEach { key ->
                        newMap[key.toLong()] = payload.getJSONArray(key).map { parseEventFromString(it.toString()) }
                    }
                    eventMap = newMap
                    "successfully uploaded events"
                }
                else "invalid message: $message"
            }
        }
    }

    fun evalEventInjection(currentTick: Long, eventProcessor: EventProcessor) {
        if (isInjectingEvents) {
            //fetch all events where tick is less or equal than current tick
            val events = eventMap.headMap(currentTick + 1)

            for ((_, eventList) in events) {
                for (event in eventList) {
                    eventProcessor.publishExternalEvent(event)
                }
            }

            //only remove fetched events
            events.clear()
        }
    }
}

@AutoService(FeatureProvider::class)
class EventInjectionProvider : FeatureProvider {
    override val registryName: String = "EventInjection"
    override val registryClass: KClass<out Feature> = EventInjectionFeature::class
    override val featureDependencies: Set<KClass<out Feature>> = emptySet()

    override fun createFeature(manager: FeatureManager): Feature {
        return EventInjectionFeature()
    }

}