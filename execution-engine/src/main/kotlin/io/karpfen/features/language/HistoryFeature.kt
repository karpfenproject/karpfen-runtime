package io.karpfen.io.karpfen.features.language

import com.google.auto.service.AutoService
import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import io.karpfen.dsl.states.History
import io.karpfen.dsl.states.Transition
import io.karpfen.dsl.states.conditions.Condition
import io.karpfen.dsl.states.conditions.ConditionType
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class HistoryFeature: DefaultFeature() {

    /**
     * Map from model element to states to their last visited substate. Both levels are concurrent, because with the
     * feature TrueParallel the branches of one state machine record and read their histories on threads of their own.
     * Branches are in different regions, so they write different states of the inner map.
     */
    private val lastVisitedSubstates = ConcurrentHashMap<String, MutableMap<String, String>>()

    /**
     * Records the current stack in the history of its states. Every state of [currentStateStack] except the innermost
     * one is an outer state of what is active now. One with a shallow history remembers its directly nested state on
     * the stack, one with a deep history the innermost state.
     */
    fun updateHistory(modelElementId: String, currentStateStack: List<io.karpfen.dsl.states.State>) {
        if (currentStateStack.size < 2) return

        val currentStateName = currentStateStack.last().name
        val remembered = HashMap<String, String>()

        for (i in 0 until currentStateStack.size - 1) {
            val state = currentStateStack[i]
            when (state.history) {
                History.SHALLOW -> remembered[state.name] = currentStateStack[i + 1].name
                History.DEEP -> remembered[state.name] = currentStateName
                History.NONE -> Unit
            }
        }

        if (remembered.isNotEmpty()) {
            lastVisitedSubstates.getOrPut(modelElementId) { ConcurrentHashMap() }.putAll(remembered)
        }
    }

    fun createTransitionOrNull(modelElementId: String, currentState: io.karpfen.dsl.states.State): Transition? {

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