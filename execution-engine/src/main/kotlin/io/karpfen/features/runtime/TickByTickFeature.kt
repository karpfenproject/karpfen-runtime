package io.karpfen.io.karpfen.features.runtime

import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import java.util.concurrent.Semaphore
import kotlin.reflect.KClass

class TickByTickFeature(explicitlyRequested: Boolean) : DefaultFeature(explicitlyRequested) {

    private val semaphore = Semaphore(0)

    @Volatile
    private var isPaused: Boolean = false

    private fun pause(): String {
        isPaused = true
        semaphore.drainPermits()
        return "execution paused"
    }

    private fun resume(): String {
        isPaused = false
        semaphore.release()
        return "execution resumed"
    }

    private fun tick(amount: Int): String {
        if (amount < 1) return "amount of ticks must not be less than one"
        if (isPaused && semaphore.availablePermits() == 0) {
            semaphore.release(amount)
        } else {
            return "execution is currently not paused, ticks will not be applied"
        }
        return "execution is advancing $amount tick${if (amount > 1) "s" else ""}"
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
    override fun onMessage(message: String): String {
        return when (message) {
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

    override val featureDependencies: Set<KClass<out Feature>> = mutableSetOf()

    override fun createFeature(explicitlyRequested: Boolean, manager: FeatureManager): Feature {
        return TickByTickFeature(explicitlyRequested)
    }
}