package io.karpfen.features.runtime.event

import io.karpfen.features.compareEvent
import io.karpfen.features.createEngine
import io.karpfen.features.createEngineAndRunEngineCycle
import io.karpfen.features.globalMetamodelId
import io.karpfen.features.stopEngine
import io.karpfen.io.karpfen.exec.EventProcessor
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.runtime.event.EventRecordingFeature
import io.karpfen.io.karpfen.features.runtime.event.parseEventFromString
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.PayloadFormat
import io.mockk.InternalPlatformDsl.toArray
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import kotlin.test.*

class EventRecordingTest {

    lateinit var manager: FeatureManager

    lateinit var events: MutableList<Event>

    fun setup(): EventRecordingFeature {
        manager = FeatureManager()
        manager.requestFeatureActivation(EventRecordingFeature::class)
        events = mutableListOf()
        events.add(Event("domain1", "name1", "payload1", payloadFormat = PayloadFormat.NONE))
        events.add(Event("domain2", "name2", "payload2", payloadFormat = PayloadFormat.JSON))
        events.add(Event("domain3", "name3", "payload3", payloadFormat = PayloadFormat.KMODEL))
        return manager.getActiveFeature<EventRecordingFeature>()
    }

    fun getStatemachine(): String {
        return "STATEMACHINE ATTACHED TO \"$globalMetamodelId\" {\n" +
                "\tSTATES {\n" +
                "\t\tINITIAL STATE \"first\" {}\n" +
                "\t\tSTATE \"second\" {}\n" +
                "\t\tSTATE \"third\" {}\n" +
                "\t\tSTATE \"fourth\" {}\n" +
                "\t}\n" +
                "\tTRANSITIONS {\n" +
                "\t\tTRANSITION \"first\" -> \"second\" {CONDITION { EVENT(\"domain1\", \"name1\") }}\n" +
                "\t\tTRANSITION \"second\" -> \"third\" {CONDITION { EVENT(\"domain2\", \"name2\") }}\n" +
                "\t\tTRANSITION \"third\" -> \"fourth\" {CONDITION { EVENT(\"domain3\", \"name3\") }}\n" +
                "\t}\n" +
                "}".trimIndent()
    }

    @Test
    fun testEventRecordingActivation() {
        assertDoesNotThrow { setup() }
    }

    @Test
    fun testEventRecordingDeactivation() {
        val eventRecordingFeature = setup()

        val eventsPassed = mutableListOf<Event>()

        val eventProcessor = mockk<EventProcessor>()

        every { eventProcessor.publishExternalEvent(any<Event>()) } answers {
            eventsPassed.add(firstArg<Event>())
        }

        eventRecordingFeature.onMessage("start")
        eventRecordingFeature.addEventToQueue(events[0], eventProcessor)

        manager.requestFeatureDeactivation(EventRecordingFeature::class)

        assertEquals("{}", eventRecordingFeature.onMessage("get"))
        assertContentEquals(listOf(events[0]), eventsPassed)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testEventRecordingMessaging() {
        val eventRecordingFeature = setup()

        val isRecordingField = EventRecordingFeature::class.java.getDeclaredField("isRecording")
        isRecordingField.setAccessible(true)

        val eventMapField = EventRecordingFeature::class.java.getDeclaredField("eventMap")
        eventMapField.setAccessible(true)
        val eventMap = eventMapField.get(eventRecordingFeature) as MutableMap<Long, MutableList<Event>>

        assertTrue(eventRecordingFeature.onMessage("invalid").startsWith("invalid message: "))

        assertEquals("recording started", eventRecordingFeature.onMessage("start"))
        assertTrue(isRecordingField.getBoolean(eventRecordingFeature))

        assertEquals("recording stopped", eventRecordingFeature.onMessage("stop"))
        assertFalse(isRecordingField.getBoolean(eventRecordingFeature))

        assertEquals("{}", eventRecordingFeature.onMessage("get"))
        eventMap[1] = mutableListOf(events[0], events[1])
        eventMap[10] = mutableListOf(events[2])
        assertEquals(
            "{\"1\":[\"{\\\"messageType\\\":\\\"name1\\\",\\\"payload\\\":\\\"payload1\\\",\\\"payloadFormat\\\":\\\"NONE\\\",\\\"environmentKey\\\":\\\"domain1\\\"}\",\"{\\\"messageType\\\":\\\"name2\\\",\\\"payload\\\":\\\"payload2\\\",\\\"payloadFormat\\\":\\\"JSON\\\",\\\"environmentKey\\\":\\\"domain2\\\"}\"],\"10\":[\"{\\\"messageType\\\":\\\"name3\\\",\\\"payload\\\":\\\"payload3\\\",\\\"payloadFormat\\\":\\\"KMODEL\\\",\\\"environmentKey\\\":\\\"domain3\\\"}\"]}",
            eventRecordingFeature.onMessage("get")
        )

        assertEquals("cleared recorded events", eventRecordingFeature.onMessage("clear"))
        assertEquals("{}", eventRecordingFeature.onMessage("get"))
    }

    @Test
    fun testRecordAndSetCurrentTick() {
        val eventRecordingFeature = setup()

        val eventsPassed = mutableListOf<Event>()

        val eventProcessor = mockk<EventProcessor>()

        every { eventProcessor.publishExternalEvent(any<Event>()) } answers {
            eventsPassed.add(firstArg<Event>())
        }

        eventRecordingFeature.addEventToQueue(events[0], eventProcessor)
        eventRecordingFeature.recordQueuedEvents(1, eventProcessor)
        assertEquals("{}", eventRecordingFeature.onMessage("get"))

        eventRecordingFeature.onMessage("start")
        eventRecordingFeature.addEventToQueue(events[0], eventProcessor)
        eventRecordingFeature.recordQueuedEvents(1, eventProcessor)
        eventRecordingFeature.addEventToQueue(events[1], eventProcessor)
        eventRecordingFeature.addEventToQueue(events[2], eventProcessor)
        eventRecordingFeature.recordQueuedEvents(10, eventProcessor)
        eventRecordingFeature.addEventToQueue(events[0], eventProcessor)
        assertEquals(
            "{\"1\":[\"{\\\"messageType\\\":\\\"name1\\\",\\\"payload\\\":\\\"payload1\\\",\\\"payloadFormat\\\":\\\"NONE\\\",\\\"environmentKey\\\":\\\"domain1\\\"}\"],\"10\":[\"{\\\"messageType\\\":\\\"name2\\\",\\\"payload\\\":\\\"payload2\\\",\\\"payloadFormat\\\":\\\"JSON\\\",\\\"environmentKey\\\":\\\"domain2\\\"}\",\"{\\\"messageType\\\":\\\"name3\\\",\\\"payload\\\":\\\"payload3\\\",\\\"payloadFormat\\\":\\\"KMODEL\\\",\\\"environmentKey\\\":\\\"domain3\\\"}\"]}",
            eventRecordingFeature.onMessage("get")
        )
        assertContentEquals(listOf(events[0], events[1], events[2]), eventsPassed)
    }

    @Test
    fun testEventRecordingIntegration() {
        setup()

        manager.onMessage(EventRecordingFeature::class, "start")

        val engine = createEngineAndRunEngineCycle(getStatemachine(), manager, false)

        engine.receiveExternalEvent(events[0])
        engine.receiveExternalEvent(events[1])
        Thread.sleep(50)
        engine.receiveExternalEvent(events[2])
        Thread.sleep(50)

        stopEngine(engine)

        val jsonObject = JSONObject(manager.onMessage(EventRecordingFeature::class, "get"))

        var i = 0

        for (key in jsonObject.keys().asSequence().sorted()) {
            for (eventString in jsonObject.getJSONArray(key)) {
                val event = parseEventFromString(eventString as String)
                compareEvent(events[i], event)
                i++
            }
        }
    }
}