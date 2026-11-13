package io.karpfen.features.runtime.event

import io.karpfen.features.*
import io.karpfen.io.karpfen.exec.EventProcessor
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.runtime.event.EventInjectionFeature
import io.karpfen.io.karpfen.features.runtime.event.parseStringFromEvent
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.PayloadFormat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventInjectionTest {

    lateinit var manager: FeatureManager

    lateinit var event1: Event
    lateinit var event2: Event
    lateinit var event3: Event

    fun setup(): EventInjectionFeature {
        manager = FeatureManager()
        manager.requestFeatureActivation(EventInjectionFeature::class)
        event1 = Event("domain1", "name1", "payload1", payloadFormat = PayloadFormat.NONE)
        event2 = Event("domain2", "name2", "payload2", payloadFormat = PayloadFormat.JSON)
        event3 = Event("domain3", "name3", "payload3", payloadFormat = PayloadFormat.KMODEL)
        return manager.getActiveFeature<EventInjectionFeature>()
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
    fun testEventPlaybackActivation() {
        assertDoesNotThrow { setup() }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testEventPlaybackMessaging() {
        val eventInjectionFeature = setup()

        val isInjectingEventsField = EventInjectionFeature::class.java.getDeclaredField("isInjectingEvents")
        isInjectingEventsField.setAccessible(true)

        val eventMapField = EventInjectionFeature::class.java.getDeclaredField("eventMap")
        eventMapField.setAccessible(true)
        val eventMap = eventMapField.get(eventInjectionFeature) as MutableMap<Long, List<Event>>

        assertTrue(eventInjectionFeature.onMessage("invalid").startsWith("invalid message: "))

        assertEquals("injection started", eventInjectionFeature.onMessage("start"))
        assertTrue(isInjectingEventsField.getBoolean(eventInjectionFeature))

        assertEquals("injection stopped", eventInjectionFeature.onMessage("stop"))
        assertFalse(isInjectingEventsField.getBoolean(eventInjectionFeature))

        assertEquals("{}", eventInjectionFeature.onMessage("get"))
        eventMap[1] = mutableListOf(event1, event2)
        eventMap[10] = mutableListOf(event3)
        assertEquals(
            "{\"1\":[\"{\\\"messageType\\\":\\\"name1\\\",\\\"payload\\\":\\\"payload1\\\",\\\"payloadFormat\\\":\\\"NONE\\\",\\\"environmentKey\\\":\\\"domain1\\\"}\",\"{\\\"messageType\\\":\\\"name2\\\",\\\"payload\\\":\\\"payload2\\\",\\\"payloadFormat\\\":\\\"JSON\\\",\\\"environmentKey\\\":\\\"domain2\\\"}\"],\"10\":[\"{\\\"messageType\\\":\\\"name3\\\",\\\"payload\\\":\\\"payload3\\\",\\\"payloadFormat\\\":\\\"KMODEL\\\",\\\"environmentKey\\\":\\\"domain3\\\"}\"]}",
            eventInjectionFeature.onMessage("get")
        )

        assertEquals("cleared event queue", eventInjectionFeature.onMessage("clear"))
        assertEquals("{}", eventInjectionFeature.onMessage("get"))

        val jsonObject = "{\"1\":[${parseStringFromEvent(event1)},${parseStringFromEvent(event2)}],\"10\":[${parseStringFromEvent(event3)}]}"

        assertEquals("successfully uploaded events", eventInjectionFeature.onMessage("upload:${jsonObject}"))
        assertEquals(
            "{\"1\":[\"{\\\"messageType\\\":\\\"name1\\\",\\\"payload\\\":\\\"payload1\\\",\\\"payloadFormat\\\":\\\"NONE\\\",\\\"environmentKey\\\":\\\"domain1\\\"}\",\"{\\\"messageType\\\":\\\"name2\\\",\\\"payload\\\":\\\"payload2\\\",\\\"payloadFormat\\\":\\\"JSON\\\",\\\"environmentKey\\\":\\\"domain2\\\"}\"],\"10\":[\"{\\\"messageType\\\":\\\"name3\\\",\\\"payload\\\":\\\"payload3\\\",\\\"payloadFormat\\\":\\\"KMODEL\\\",\\\"environmentKey\\\":\\\"domain3\\\"}\"]}",
            eventInjectionFeature.onMessage("get"))
    }

    @Test
    fun testEvalEventInjection() {
        val eventInjectionFeature = setup()

        val injectedEvents = mutableListOf<Event>()

        val eventProcessor = mockk<EventProcessor>()

        every { eventProcessor.publishExternalEvent(any<Event>()) } answers {
            injectedEvents.add(firstArg<Event>())
        }

        eventInjectionFeature.onMessage("upload:{\"1\":[${parseStringFromEvent(event1)},${parseStringFromEvent(event2)}],\"10\":[${parseStringFromEvent(event3)}]}")

        eventInjectionFeature.evalEventInjection(1, eventProcessor)

        assertEquals(0, injectedEvents.size)

        eventInjectionFeature.onMessage("start")
        eventInjectionFeature.evalEventInjection(1, eventProcessor)
        assertEquals(2, injectedEvents.size)
        compareEvent(event1, injectedEvents[0])
        compareEvent(event2, injectedEvents[1])

        eventInjectionFeature.evalEventInjection(9, eventProcessor)
        assertEquals(2, injectedEvents.size)
        compareEvent(event1, injectedEvents[0])
        compareEvent(event2, injectedEvents[1])

        eventInjectionFeature.evalEventInjection(10, eventProcessor)
        assertEquals(3, injectedEvents.size)
        compareEvent(event1, injectedEvents[0])
        compareEvent(event2, injectedEvents[1])
        compareEvent(event3, injectedEvents[2])
    }

    @Test
    fun testEventPlaybackIntegration() {
        setup()

        manager.onMessage(EventInjectionFeature::class, "upload:{\"1\":[${parseStringFromEvent(event1)}],\"5\":[${parseStringFromEvent(event2)}],\"10\":[${parseStringFromEvent(event3)}]}")
        manager.onMessage(EventInjectionFeature::class, "start")

        val engine = createEngineAndRunEngineCycle(getStatemachine(), manager, true)

        assertEquals("fourth", getLastEnteredState(engine.traceLogger!!))

        assertTransitionHappenedAt(engine, 1, "first", "second")

        assertTransitionHappenedAt(engine, 5, "second", "third")

        assertTransitionHappenedAt(engine, 10, "third", "fourth")
    }
}