package io.karpfen.io.karpfen.features.runtime

import com.google.auto.service.AutoService
import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

class TickByTickFeature : DefaultFeature() {

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

    /**
     * How a thread of the engine waits while it is paused: it is handed the wait and runs it. The engine sets it so
     * that a pause inside a tick, at a breakpoint, leaves the tick gate open for readers.
     */
    @Volatile
    var waiting: (wait: () -> Unit) -> Unit = { wait -> wait() }

    /** True while a thread of the engine waits in [evalPausedState] for a resume or for ticks. */
    @Volatile
    private var isWaiting: Boolean = false

    /** Where the engine waits at a breakpoint right now, or null while it does not. */
    @Volatile
    private var halt: JSONObject? = null

    /**
     * Told the [engineStatus] on the waiting thread each time that thread starts to wait in [evalPausedState], before
     * it blocks, and each time it goes on, right after it wakes. The engine sets it so that the environment can tell
     * its clients.
     */
    @Volatile
    var onStatusChanged: (status: String) -> Unit = {}

    /**
     * Lets one thread at a time wait in [evalPausedState] and [haltAt]. While the feature TrueParallel is on, several
     * branches can reach a breakpoint in the same tick on threads of their own; they halt one after the other, each
     * with its own [halt] in the status, and a resume or a tick releases the one that waits. Fair, so the halts come in
     * the order in which the branches reached them.
     */
    private val waitLock = ReentrantLock(true)

    /** True once a pause was asked for and until the engine is resumed: the engine waits, or will at its next stop. */
    fun isPaused(): Boolean = isPaused

    /** True while a thread of the engine waits, between two ticks or at a breakpoint. */
    fun isWaiting(): Boolean = isWaiting

    /**
     * What the engine is doing, for a debugger, as a JSON object: `paused` ([isPaused]), `waiting` ([isWaiting]) and,
     * only while the engine waits at a breakpoint, `halt`, the place it waits at.
     */
    fun engineStatus(): String {
        val status = JSONObject()
        status.put("paused", isPaused)
        status.put("waiting", isWaiting)
        halt?.let { status.put("halt", it) }
        return status.toString()
    }

    /**
     * Lets the calling thread wait while a pause was asked for, until a resume or ticks release it; otherwise returns
     * at once. [halt] is the breakpoint the engine waits at, or null for a wait between two ticks; the [engineStatus]
     * names it while the thread waits. The thread reports the status through [onStatusChanged] once it waits, before
     * it blocks, and again once it goes on.
     */
    fun evalPausedState(halt: JSONObject? = null) = waitLock.withLock {
        if (isPaused) {
            this.halt = halt
            isWaiting = true
            try {
                onStatusChanged(engineStatus())
                waiting { semaphore.acquire() }
            } finally {
                isWaiting = false
                this.halt = null
                onStatusChanged(engineStatus())
            }
        }
    }

    /**
     * Pauses the run and lets the calling thread wait at the breakpoint [halt] as [evalPausedState] describes. Pausing
     * and waiting happen together while this thread holds the wait lock, so a branch that reaches a breakpoint while
     * another one waits at its own halts after that one went on, instead of losing its halt to the other's resume.
     */
    fun haltAt(halt: JSONObject) = waitLock.withLock {
        pause()
        evalPausedState(halt)
    }

    override fun onDeactivate() {
        resume()
    }

    //Supported Messages: pause, resume, status, tick:{Int}
    override fun onMessage(message: String): String {
        return when (message) {
            "pause" -> pause()
            "resume" -> resume()
            "status" -> if (isPaused) "paused" else "running"
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

@AutoService(FeatureProvider::class)
class TickByTickProvider : FeatureProvider {
    override val registryName = "TickByTick"

    override val registryClass = TickByTickFeature::class

    override val featureDependencies: Set<KClass<out Feature>> = emptySet()

    override fun createFeature(manager: FeatureManager): Feature {
        return TickByTickFeature()
    }
}