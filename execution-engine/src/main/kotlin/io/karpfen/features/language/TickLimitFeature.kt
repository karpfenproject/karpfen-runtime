package io.karpfen.io.karpfen.features.language

import com.google.auto.service.AutoService
import io.karpfen.io.karpfen.exec.SMContext
import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import states.State
import states.Transition
import states.conditions.Condition
import states.conditions.ConditionType
import kotlin.reflect.KClass

class TickLimitFeature: DefaultFeature() {

    /** Keeps track of how many ticks every state of a state machine has been visited */
    private var tickMap: MutableMap<String, MutableMap<String, Long>> = HashMap()

    /** Increments the tick counter of every currently visited state, removes unvisited states from the tickMap */
    fun incrementTick(contexts: List<SMContext>) {
        val newTickMap = HashMap<String, MutableMap<String, Long>>()
        for (ctx in contexts) {
            val modelElementId = ctx.modelElementId
            val oldStateMap = tickMap[modelElementId] ?: emptyMap()
            val newStateMap = HashMap<String, Long>()
            newTickMap[modelElementId] = newStateMap
            for (branch in ctx.branches) {
                for (stateName in branch.stateStack) {
                    newStateMap[stateName] = (oldStateMap[stateName] ?: 0L) + 1L
                }
            }
        }
        tickMap = newTickMap
    }

    /** Evaluates if every state of the current stack has passed their specified delay, if so returns true */
    fun isTransitionBlocked(modelElementId: String, stateStack: List<State>): Boolean {
        val stateMap = tickMap[modelElementId]?: emptyMap()
        stateStack.forEach { state ->
            //No delay specified, ignore
            if (state.delay == null) return@forEach
            if ((stateMap[state.name]?: 0L) <= state.delay!!) return true
        }
        return false
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