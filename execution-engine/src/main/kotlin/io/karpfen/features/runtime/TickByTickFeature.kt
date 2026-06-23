package io.karpfen.io.karpfen.features.runtime

import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import java.util.concurrent.Semaphore

class TickByTickFeature(isUserAdded: Boolean) : DefaultFeature(isUserAdded) {

    private val semaphore = Semaphore(0)

    @Volatile
    private var isPaused: Boolean = false

    fun pause() {
        isPaused = true;
        semaphore.drainPermits()
    }

    fun resume() {
        isPaused = false
        semaphore.release()
    }

    fun tick(amount: Int) {
        if (amount <= 0) return
        if (isPaused && semaphore.availablePermits() == 0) {
            semaphore.release(amount)
        }
    }

    fun checkPausedState() {
        if (isPaused) {
            semaphore.acquire()
        }
    }

    override fun onDeactivate() {
        resume()
    }

    //Supported Messages: pause, resume, tick:{Int}
    override fun onMessage(message: String) {
        when (message) {
            "pause" -> pause()
            "resume" -> resume()
            else -> {
                val list = message.split(':')
                if (list.size == 2 && list[0] == "tick" && list[1].toIntOrNull() != null) {
                    tick(list[1].toInt())
                } else {
                    throw IllegalArgumentException("Invalid message: $message")
                }
            }
        }
    }
}

class TickByTickProvider : FeatureProvider {
    override val registryName = "TickByTick"

    override val registryClass = TickByTickFeature::class

    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager): Feature {
        return TickByTickFeature(isUserAdded)
    }
}