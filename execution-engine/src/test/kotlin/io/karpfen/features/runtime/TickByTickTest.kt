package io.karpfen.features.runtime

import io.karpfen.io.karpfen.features.FeatureFactory
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.runtime.TickByTickFeature
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Semaphore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

class TickByTickTest {

    @Test
    fun testTickByTickCreation() {
        val tickByTick = assertDoesNotThrow {
            FeatureFactory.createFeature(TickByTickFeature::class, FeatureManager())
        }

        assertTrue(tickByTick is TickByTickFeature)

        val isPausedField = tickByTick::class.java.getDeclaredField("isPaused")
        isPausedField.setAccessible(true)

        val semaphoreField = tickByTick::class.java.getDeclaredField("semaphore")
        semaphoreField.setAccessible(true)

        assertFalse(isPausedField.getBoolean(tickByTick))

        assertEquals(0, (semaphoreField.get(tickByTick) as Semaphore).availablePermits())
    }

    @Test
    fun testTickByTickCallbacks() {
        val tickByTick = FeatureFactory.createFeature(TickByTickFeature::class, FeatureManager()) as TickByTickFeature

        val isPausedField = tickByTick::class.java.getDeclaredField("isPaused")
        isPausedField.setAccessible(true)

        val semaphoreField = tickByTick::class.java.getDeclaredField("semaphore")
        semaphoreField.setAccessible(true)
        val semaphore = semaphoreField.get(tickByTick) as Semaphore

        //pause
        assertEquals("execution paused", tickByTick.onMessage("pause"))
        assertTrue(isPausedField.getBoolean(tickByTick))
        assertEquals(0, semaphore.availablePermits())

        //resume
        assertEquals("execution resumed", tickByTick.onMessage("resume"))
        assertFalse(isPausedField.getBoolean(tickByTick))
        assertEquals(1, semaphore.availablePermits())

        //pause again
        assertEquals("execution paused", tickByTick.onMessage("pause"))
        assertTrue(isPausedField.getBoolean(tickByTick))
        assertEquals(0, semaphore.availablePermits())

        //perform 1 tick
        assertEquals("execution is advancing 1 tick", tickByTick.onMessage("tick:1"))
        assertTrue(isPausedField.getBoolean(tickByTick))
        assertEquals(1, semaphore.availablePermits())

        //reset
        tickByTick.onMessage("pause")

        //perform 5 ticks
        assertEquals("execution is advancing 5 ticks", tickByTick.onMessage("tick:5"))
        assertTrue(isPausedField.getBoolean(tickByTick))
        assertEquals(5, semaphore.availablePermits())

        //perform additional tick
        assertEquals("execution is currently not paused, ticks will not be applied", tickByTick.onMessage("tick:1"))
        assertEquals(5, semaphore.availablePermits())

        //perform 0 ticks
        assertEquals("amount of ticks must not be less than one", tickByTick.onMessage("tick:0"))
        assertEquals(5, semaphore.availablePermits())

        //resume again
        assertEquals("execution resumed", tickByTick.onMessage("resume"))
        assertFalse(isPausedField.getBoolean(tickByTick))
        assertEquals(6, semaphore.availablePermits())

        //pause one last time
        assertEquals("execution paused", tickByTick.onMessage("pause"))
        assertTrue(isPausedField.getBoolean(tickByTick))
        assertEquals(0, semaphore.availablePermits())

        //deactivate
        tickByTick.onDeactivate()
        assertFalse(isPausedField.getBoolean(tickByTick))
        assertEquals(1, semaphore.availablePermits())

        //send invalid message
        val exception = assertThrows<IllegalArgumentException> {
            tickByTick.onMessage("invalid")
        }

        assertEquals(exception.message, "Invalid message: invalid")

    }

    @Test
    fun testTickByTickHalting() = runTest {
        val tickByTick = FeatureFactory.createFeature(TickByTickFeature::class, FeatureManager()) as TickByTickFeature

        var tickCounter = 0

        tickByTick.onMessage("pause")

        val execution = launch(Dispatchers.Default) {
            while (true) {
                tickByTick.checkPausedState()
                tickCounter++
                ensureActive()
            }
        }

        delay(50.milliseconds)

        assertEquals(0, tickCounter)

        tickByTick.onMessage("tick:1")

        delay(50.milliseconds)

        assertEquals(1, tickCounter)

        tickByTick.onMessage("tick:10")

        delay(50.milliseconds)

        assertEquals(11, tickCounter)

        execution.cancel()

        tickByTick.onMessage("resume")
    }
}