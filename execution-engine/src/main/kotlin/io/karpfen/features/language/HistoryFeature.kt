package io.karpfen.io.karpfen.features.language

import com.google.auto.service.AutoService
import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import states.History
import states.Transition
import states.conditions.Condition
import states.conditions.ConditionType
import kotlin.reflect.KClass

class HistoryFeature: DefaultFeature() {

    /** Map from model element to states to their last visited substate */
    private val lastVisitedSubstates: MutableMap<String, MutableMap<String, String>> = HashMap()

    /**
     * Records the current stack in the history of its states. Every state of [currentStateStack] except the innermost
     * one is an outer state of what is active now. One with a shallow history remembers its directly nested state on
     * the stack, one with a deep history the innermost state.
     */
    fun updateHistory(modelElementId: String, currentStateStack: List<states.State>) {
        if (currentStateStack.size < 2) return

        val currentStateName = currentStateStack.last().name
        val stateMap = lastVisitedSubstates.getOrPut(modelElementId) { mutableMapOf() }

        for (i in 0 until currentStateStack.size - 1) {
            val state = currentStateStack[i]
            when (state.history) {
                History.SHALLOW -> stateMap[state.name] = currentStateStack[i + 1].name
                History.DEEP -> stateMap[state.name] = currentStateName
                History.NONE -> Unit
            }
        }

        if (stateMap.isEmpty()) lastVisitedSubstates.remove(modelElementId)
    }

    fun createTransitionOrNull(modelElementId: String, currentState: states.State): Transition? {

        val lastVisitedSubstate = lastVisitedSubstates[modelElementId]?.get(currentState.name)

        if (currentState.history != History.NONE && lastVisitedSubstate != null) {
            return Transition(
                fromState = currentState.name,
                toState = lastVisitedSubstate,
                allowLoops = false,
                condition = Condition(ConditionType.VALUE)
            )
        }

        return null
    }

    fun resetHistory() {
        lastVisitedSubstates.clear()
    }
}

@AutoService(FeatureProvider::class)
class HistoryProvider: FeatureProvider {
    override val registryName = "History"
    override val registryClass = HistoryFeature::class
    override val featureDependencies: Set<KClass<out Feature>> = emptySet()

    override fun createFeature(manager: FeatureManager): Feature {
        return HistoryFeature()
    }
}