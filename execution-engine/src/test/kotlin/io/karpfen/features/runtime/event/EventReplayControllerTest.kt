package io.karpfen.features.runtime.event

import io.karpfen.features.createEngine
import io.karpfen.features.createEngineAndRunEngineCycle
import io.karpfen.features.getAllTransitionsWithTicks
import io.karpfen.features.globalMetamodelId
import io.karpfen.features.stopEngine
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.runtime.event.EventInjectionFeature
import io.karpfen.io.karpfen.features.runtime.event.EventRecordingFeature
import io.karpfen.io.karpfen.features.runtime.event.EventReplayControllerFeature
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.PayloadFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventReplayControllerTest {

    lateinit var manager: FeatureManager

    fun setup(): EventReplayControllerFeature {
        manager = FeatureManager()
        manager.requestFeatureActivation(EventReplayControllerFeature::class)
        return manager.getActiveFeature<EventReplayControllerFeature>()
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
    fun testEventReplayControllerActivation() {
        val eventReplayController = assertDoesNotThrow { setup() }

        val recordingField = EventReplayControllerFeature::class.java.getDeclaredField("recordingFeature")
        val playbackField = EventReplayControllerFeature::class.java.getDeclaredField("playbackFeature")
        recordingField.setAccessible(true)
        playbackField.setAccessible(true)
        val recordingFeature = recordingField.get(eventReplayController) as EventRecordingFeature
        val playbackFeature = playbackField.get(eventReplayController) as EventInjectionFeature

        assertEquals(manager.getActiveFeature<EventRecordingFeature>(), recordingFeature)
        assertEquals(manager.getActiveFeature<EventInjectionFeature>(), playbackFeature)
    }

    @Test
    fun testEventReplayControllerMessaging() {
        val eventReplayController = setup()

        assertTrue(eventReplayController.onMessage("invalid").startsWith("invalid message: "))

        assertEquals("replay successfully initialized", eventReplayController.onMessage("initialize"))
    }

    @Test
    fun testEventReplayControllerIntegration() {
        setup()

        val event1 = Event("domain1", "name1", "payload1", payloadFormat = PayloadFormat.NONE)
        val event2 = Event("domain2", "name2", "payload2", payloadFormat = PayloadFormat.JSON)
        val event3 = Event("domain3", "name3", "payload3", payloadFormat = PayloadFormat.KMODEL)

        manager.onMessage(EventRecordingFeature::class, "start")

        var engine = createEngineAndRunEngineCycle(getStatemachine(), manager, false)

        engine.receiveExternalEvent(event1)
        engine.receiveExternalEvent(event2)
        Thread.sleep(50)
        engine.receiveExternalEvent(event3)
        Thread.sleep(50)

        stopEngine(engine)

        val recordDetails = getAllTransitionsWithTicks(engine.traceLogger!!)

        manager.onMessage(EventReplayControllerFeature::class, "initialize")

        manager.onMessage(EventInjectionFeature::class, "start")

        engine = createEngineAndRunEngineCycle(getStatemachine(), manager, false)

        //ensure the second loop simulates at least the same amount of ticks
        Thread.sleep(200)

        stopEngine(engine)

        val playbackDetails = getAllTransitionsWithTicks(engine.traceLogger!!)

        assertEquals(recordDetails.size, playbackDetails.size)

        for ((tick, recordTransition) in recordDetails) {
            val playbackTransition = playbackDetails[tick]

            assertNotNull(playbackTransition)
            assertEquals(recordTransition["from"], playbackTransition["from"])
            assertEquals(recordTransition["to"], playbackTransition["to"])
        }
    }
}