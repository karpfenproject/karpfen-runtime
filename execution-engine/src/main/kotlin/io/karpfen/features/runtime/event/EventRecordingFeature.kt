package io.karpfen.io.karpfen.features.runtime.event

import com.google.auto.service.AutoService
import io.karpfen.io.karpfen.exec.EventProcessor
import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import io.karpfen.io.karpfen.messages.Event
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

class EventRecordingFeature: DefaultFeature() {

    @Volatile
    private var isRecording: Boolean = false

    private val lock = ReentrantLock()

    private var eventQueue = ArrayList<Event>()

    private val eventMap = hashMapOf<Long, MutableList<Event>>()

    private var eventProcessor: EventProcessor? = null

    override fun onDeactivate() {
        val currentTickEvents = lock.withLock {
            isRecording = false
            val oldQueue = eventQueue
            eventQueue = ArrayList()
            oldQueue
        }
        for (event in currentTickEvents) {
            eventProcessor?.publishExternalEvent(event)
        }
    }

    override fun onMessage(message: String): String {
        return when (message) {
            "start" -> {
                lock.withLock {
                    isRecording = true
                }
                "recording started"
            }
            "stop" -> {
                onDeactivate()
                "recording stopped"
            }
            "clear" -> {
                eventMap.clear()
                "cleared recorded events"
            }
            "get" -> {
                JSONObject(eventMap.mapValues { (_, eventList) -> eventList.map { parseStringFromEvent(it) } }).toString()
            }
            else -> "invalid message: $message"
        }
    }

    fun addEventToQueue(event: Event, eventProcessor: EventProcessor): Boolean {
        setEventProcessor(eventProcessor)
        return lock.withLock {
            if (isRecording) {
                eventQueue.add(event)
            }
            isRecording
        }
    }

    fun recordQueuedEvents(currentTick: Long, eventProcessor: EventProcessor) {
        setEventProcessor(eventProcessor)
        val currentTickEvents = lock.withLock {
            if (!isRecording) return
            val oldQueue = eventQueue
            eventQueue = ArrayList()
            oldQueue
        }
        if (currentTickEvents.isNotEmpty()) {
            for (event in currentTickEvents) {
                eventProcessor.publishExternalEvent(event)
            }
            eventMap[currentTick] = currentTickEvents
        }
    }

    //Initialize event processor
    private fun setEventProcessor(eventProcessor: EventProcessor) {
        if (this.eventProcessor == null) {
            this.eventProcessor = eventProcessor
        }
    }
}

@AutoService(FeatureProvider::class)
class EventRecordingProvider: FeatureProvider {
    override val registryName: String = "EventRecording"
    override val registryClass: KClass<out Feature> = EventRecordingFeature::class
    override val featureDependencies: Set<KClass<out Feature>> = emptySet()

    override fun createFeature(manager: FeatureManager): Feature {
        return EventRecordingFeature()
    }

}