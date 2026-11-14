package io.karpfen.io.karpfen.features.language

import com.google.auto.service.AutoService
import io.karpfen.io.karpfen.exec.SMContext
import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import io.karpfen.dsl.states.State
import io.karpfen.dsl.states.Transition
import io.karpfen.dsl.states.conditions.Condition
import io.karpfen.dsl.states.conditions.ConditionType
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class TickLimitFeature: DefaultFeature() {

    /**
     * Keeps track of how many ticks every state of a state machine has been visited. The engine thread replaces the
     * whole map at the start of each tick; the inner maps are concurrent, because with the feature TrueParallel the
     * branches of one state machine read and reset their counts on threads of their own during the tick.
     */
    @Volatile
    private var tickMap: Map<String, MutableMap<String, Long>> = HashMap()

    /** Increments the tick counter of every currently visited state, removes unvisited states from the tickMap */
    fun incrementTick(contexts: List<SMContext>) {
        val newTickMap = HashMap<String, MutableMap<String, Long>>()
        for (ctx in contexts) {
            val modelElementId = ctx.modelElementId
            val oldStateMap = tickMap[modelElementId] ?: emptyMap()
            val newStateMap = ConcurrentHashMap<String, Long>()
            newTickMap[modelElementId] = newStateMap
            for (branch in ctx.branches) {
                for (stateName in branch.stateStack) {
                    newStateMap[stateName] = (oldStateMap[stateName] ?: 0L) + 1L
                }
            }
        }
        tickMap = newTickMap
    }

    /**
     * True if one of [exitedStates], the states a transition would leave, has a delay that has not passed yet. A state
     * with a delay of `n` ticks can be left once it was active for more than `n` ticks.
     */
    fun isTransitionBlocked(modelElementId: String, exitedStates: List<State>): Boolean {
        val stateMap = tickMap[modelElementId]?: emptyMap()
        exitedStates.forEach { state ->
            //No delay specified, ignore
            if (state.delay == null) return@forEach
            if ((stateMap[state.name]?: 0L) <= state.delay!!) return true
        }
        return false
    }

    /**
     * Forgets the ticks of [enteredStates], the states a transition has just entered or entered again, so that their
     * delays and timeouts count from this entry.
     */
    fun resetTicks(modelElementId: String, enteredStates: List<String>) {
        val stateMap = tickMap[modelElementId] ?: return
        enteredStates.forEach { stateMap.remove(it) }
    }

    /** Evaluates if every state of the current stack has passed their specified timeout, if so returns the necessary transition */
    fun evalNecessaryTransition(modelElementId: String, stateStack: List<State>): Transition? {
        val stateMap = tickMap[modelElementId]?: emptyMap()
        //traverse state stack in reverse, innermost timeouts prioritized
        stateStack.reversed().forEach { state ->
            //No timeout specified, ignore
            if (state.timeout == null || state.timeoutTo == null) return@forEach
            if ((stateMap[state.name]?: 0L) > state.timeout!!) return Transition(
                fromState = state.name,
                toState = state.timeoutTo!!,
                allowLoops = false,
                condition = Condition(ConditionType.VALUE)
            )
        }
        return null
    }
}

@AutoService(FeatureProvider::class)
class TickLimitProvider: FeatureProvider {
    override val registryName = "TickLimit"
    override val registryClass = TickLimitFeature::class
    override val featureDependencies: Set<KClass<out Feature>> = emptySet()

    override fun createFeature(manager: FeatureManager): Feature {
        return TickLimitFeature()
    }
}