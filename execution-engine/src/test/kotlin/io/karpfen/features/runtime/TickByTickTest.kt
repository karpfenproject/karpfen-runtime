package io.karpfen.features.runtime

import io.karpfen.features.*
import io.karpfen.io.karpfen.features.FeatureFactory
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.runtime.TickByTickFeature
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.PayloadFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Semaphore
import kotlin.test.*

class TickByTickTest {

    lateinit var manager: FeatureManager

    fun setup(): TickByTickFeature {
        manager = FeatureManager()
        manager.requestFeatureActivation(TickByTickFeature::class)
        return manager.getActiveFeature<TickByTickFeature>()
    }

    @Test
    fun testTickByTickActivation() {
        assertDoesNotThrow { setup() }
    }

    @Test
    fun testTickByTickDeactivation() = runTest {
        val tickByTickFeature = setup()

        var isRunning = false
        tickByTickFeature.onMessage("pause")

        backgroundScope.launch(Dispatchers.Default) {
            while (isActive) {
                tickByTickFeature.evalPausedState()
                isRunning = true
                ensureActive()
            }
        }

        Thread.sleep(100)

        assertFalse(isRunning)

        manager.requestFeatureDeactivation(TickByTickFeature::class)

        Thread.sleep(100)

        assertTrue(isRunning)
    }

    @Test
    fun testTickByTickMessaging() {
        val tickByTickFeature = setup()

        val isPausedField = TickByTickFeature::class.java.getDeclaredField("isPaused")
        val semaphoreField = TickByTickFeature::class.java.getDeclaredField("semaphore")
        isPausedField.setAccessible(true)
        semaphoreField.setAccessible(true)
        val semaphore = semaphoreField.get(tickByTickFeature) as Semaphore

        //pause
        assertEquals("execution paused", tickByTickFeature.onMessage("pause"))
        assertTrue(isPausedField.getBoolean(tickByTickFeature))
        assertEquals(0, semaphore.availablePermits())

        //resume
        assertEquals("execution resumed", tickByTickFeature.onMessage("resume"))
        assertFalse(isPausedField.getBoolean(tickByTickFeature))
        assertEquals(1, semaphore.availablePermits())

        //pause again
        assertEquals("execution paused", tickByTickFeature.onMessage("pause"))
        assertTrue(isPausedField.getBoolean(tickByTickFeature))
        assertEquals(0, semaphore.availablePermits())

        //perform 1 tick
        assertEquals("execution is advancing 1 tick", tickByTickFeature.onMessage("tick:1"))
        assertTrue(isPausedField.getBoolean(tickByTickFeature))
        assertEquals(1, semaphore.availablePermits())

        //reset
        tickByTickFeature.onMessage("pause")

        //perform 5 ticks
        assertEquals("execution is advancing 5 ticks", tickByTickFeature.onMessage("tick:5"))
        assertTrue(isPausedField.getBoolean(tickByTickFeature))
        assertEquals(5, semaphore.availablePermits())

        //perform additional tick
        assertEquals("execution is currently not paused, ticks will not be applied", tickByTickFeature.onMessage("tick:1"))
        assertEquals(5, semaphore.availablePermits())

        //perform 0 ticks
        assertEquals("amount of ticks must not be less than one", tickByTickFeature.onMessage("tick:0"))
        assertEquals(5, semaphore.availablePermits())

        //resume again
        assertEquals("execution resumed", tickByTickFeature.onMessage("resume"))
        assertFalse(isPausedField.getBoolean(tickByTickFeature))
        assertEquals(6, semaphore.availablePermits())

        //pause one last time
        assertEquals("execution paused", tickByTickFeature.onMessage("pause"))
        assertTrue(isPausedField.getBoolean(tickByTickFeature))
        assertEquals(0, semaphore.availablePermits())

        //deactivate
        tickByTickFeature.onDeactivate()
        assertFalse(isPausedField.getBoolean(tickByTickFeature))
        assertEquals(1, semaphore.availablePermits())

        //send invalid message
        val exception = assertThrows<IllegalArgumentException> {
            tickByTickFeature.onMessage("invalid")
        }

        assertEquals("Invalid message: invalid", exception.message, )
    }

    @Test
    fun testEvalPausedState() = runTest {
        val tickByTickFeature = FeatureFactory.createFeature(TickByTickFeature::class, FeatureManager()) as TickByTickFeature

        var tickCounter = 0

        tickByTickFeature.onMessage("pause")

        backgroundScope.launch(Dispatchers.Default) {
            while (isActive) {
                tickByTickFeature.evalPausedState()
                tickCounter++
                yield()
            }
        }

        Thread.sleep(100)
        assertEquals(0, tickCounter)

        tickByTickFeature.onMessage("tick:1")
        Thread.sleep(100)
        assertEquals(1, tickCounter)

        tickByTickFeature.onMessage("tick:5")
        Thread.sleep(100)
        assertEquals(6, tickCounter)
    }

    @Test
    fun testTickByTickVerification() {
        setup()

        val statemachineDefinition = "STATEMACHINE ATTACHED TO \"$globalMetamodelId\" {\n" +
                "\tSTATES {\n" +
                "\t\tINITIAL STATE \"first\" {}\n" +
                "\t\tSTATE \"second\" {}\n" +
                "\t\tSTATE \"third\" {}\n" +
                "\t\tSTATE \"fourth\" {}\n" +
                "\t\tSTATE \"fifth\" {}\n" +
                "\t}\n" +
                "\tTRANSITIONS {\n" +
                "\t\tTRANSITION \"first\" -> \"second\" {CONDITION { EVENT(\"domain\", \"start\") }}\n" +
                "\t\tTRANSITION \"second\" -> \"third\" {}\n" +
                "\t\tTRANSITION \"third\" -> \"fourth\" {}\n" +
                "\t\tTRANSITION \"fourth\" -> \"fifth\" {}\n" +
                "\t}\n" +
        "}".trimIndent()

        //idle verification
        var engine = createEngine(statemachineDefinition, manager)
        engine.receiveExternalEvent(Event("domain", "start"))
        runEngineCycle(engine, true)
        //should be on last state
        assertEquals("fifth", getCurrentState(engine.traceLogger!!))

        //pause verification
        engine = createEngineAndRunEngineCycle(statemachineDefinition, manager, false)
        manager.onMessage(TickByTickFeature::class, "pause")
        Thread.sleep(50)
        var tickNumber = assertNotNull(getCurrentTickNumber(engine.traceLogger!!))
        engine.receiveExternalEvent(Event("domain", "start"))
        Thread.sleep(50)
        //same tick number and still on first state means no tick happened (read from most recent log)
        assertEquals(tickNumber, getCurrentTickNumber(engine.traceLogger))
        assertEquals("first", getCurrentState(engine.traceLogger))

        //tick verification
        manager.onMessage(TickByTickFeature::class, "tick:1")
        Thread.sleep(50)
        //should advance 1 tick, be on second state
        assertEquals(tickNumber + 1, getCurrentTickNumber(engine.traceLogger))
        assertEquals("second", getCurrentState(engine.traceLogger))

        manager.onMessage(TickByTickFeature::class, "tick:3")
        Thread.sleep(50)
        //should advance 3 ticks (4 in total to reference), be on last state
        assertEquals(tickNumber + 4, getCurrentTickNumber(engine.traceLogger))
        assertEquals("fifth", getCurrentState(engine.traceLogger))
        stopEngine(engine)

        //resume verification
        engine = createEngineAndRunEngineCycle(statemachineDefinition, manager, false)
        manager.onMessage(TickByTickFeature::class, "pause")
        Thread.sleep(50)
        tickNumber = assertNotNull(getCurrentTickNumber(engine.traceLogger!!))
        engine.receiveExternalEvent(Event("domain", "start"))
        manager.onMessage(TickByTickFeature::class, "resume")
        Thread.sleep(50)
        assertNotEquals(tickNumber, getCurrentTickNumber(engine.traceLogger))
        assertNotEquals("first", getCurrentState(engine.traceLogger))
        stopEngine(engine)

        //deactivation verification
        engine = createEngineAndRunEngineCycle(statemachineDefinition, manager, false)
        manager.onMessage(TickByTickFeature::class, "pause")
        Thread.sleep(50)
        tickNumber = assertNotNull(getCurrentTickNumber(engine.traceLogger!!))
        engine.receiveExternalEvent(Event("domain", "start"))
        manager.requestFeatureDeactivation(TickByTickFeature::class)
        Thread.sleep(50)
        assertNotEquals(tickNumber, getCurrentTickNumber(engine.traceLogger))
        assertNotEquals("first", getCurrentState(engine.traceLogger))
        stopEngine(engine)
    }
}