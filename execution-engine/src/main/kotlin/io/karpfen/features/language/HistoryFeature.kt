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
     * Updates the history of every outer state
     * Shallow history is only updated one level above current state
     * Deep history is always updated
     */
    fun updateHistory(modelElementId: String, currentStateStack: List<states.State>) {
        if (currentStateStack.size < 2) return

        val currentStateName = currentStateStack.last().name

        val stateMap = lastVisitedSubstates.getOrDefault(modelElementId, mutableMapOf())

        //Shallow history update
        val secondToLastState = currentStateStack[currentStateStack.size - 2]
        if (secondToLastState.history != History.NONE) {
            stateMap[secondToLastState.name] = currentStateName
        }

        //Deep history update
        //Going through the list in reverse simulates the propagation of the history from inner to outer states
        //But has no benefit (yet)
        for (i in currentStateStack.size - 3 downTo 0) {
            val state = currentStateStack[i]
            if (state.history == History.DEEP) {
                stateMap[state.name] = currentStateName
            }
        }

        if (stateMap.isNotEmpty()) lastVisitedSubstates[modelElementId] = stateMap
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