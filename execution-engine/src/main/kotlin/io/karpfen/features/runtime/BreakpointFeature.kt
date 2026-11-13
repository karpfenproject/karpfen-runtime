package io.karpfen.io.karpfen.features.runtime

import com.google.auto.service.AutoService
import io.karpfen.io.karpfen.exec.StateMachineQueryHelper
import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import org.json.JSONObject
import states.State
import states.TransitionLike
import kotlin.reflect.KClass

/**
 * Feature for implementing breakpoint functionality into Karpfen
 * Utilizes break definitions specified in the kstates DSL
 * These definitions can be overridden during runtime with custom runtime rules
 */
class BreakpointFeature(private val tickByTickFeature: TickByTickFeature) : DefaultFeature() {

    /**
     * Custom breakpoint overrides defined during runtime
     * Mapped to state machines and state/transition identifier
     * Grouped into 4 parts for easier access, depending on target and timing
     */

    /** Breakpoint overrides triggering when a state is entered */
    private val entryBreakpoints = HashMap<String, MutableMap<String, Boolean>>()

    /** Breakpoint overrides triggering when a state is exited */
    private val exitBreakpoints = HashMap<String, MutableMap<String, Boolean>>()

    /** Breakpoint overrides triggering when a transition starts */
    private val startBreakpoints = HashMap<String, MutableMap<String, Boolean>>()

    /** Breakpoint overrides triggering when a transition ends */
    private val endBreakpoints = HashMap<String, MutableMap<String, Boolean>>()

    /**
     * JSON based messaging system
     * Every message must contain one of the following actions: getOverrides, addStateOverride, addTransitionOverride, removeStateOverride, removeTransitionOverride
     * getOverrides returns all currently active overrides, grouped into 4 parts (entry, exit, start, end)
     * addStateOverride requires modelElementId, stateIdentifier, timing (entry, exit) and breakpointStatus (false, true)
     * addTransitionOverride requires modelElementId, transitionIdentifier, timing (start, end) and breakpointStatus (false, true)
     * removeStateOverride requires modelElementId, stateIdentifier and timing (entry, exit)
     * removeTransitionOverride requires modelElementId, transitionIdentifier and timing (start, end)
     */
    override fun onMessage(message: String): String {
        try {
            val json = JSONObject(message)
            return when (val action = json.getString("action")) {
                "getOverrides" -> {
                    mapOf(
                        Pair("Entry Breakpoints", entryBreakpoints.toString()),
                        Pair("Exit Breakpoints", exitBreakpoints.toString()),
                        Pair("Start Breakpoints", startBreakpoints.toString()),
                        Pair("End Breakpoints", endBreakpoints.toString())
                    ).toString()
                }
                "addStateOverride" -> {
                    val modelElementId = json.getString("modelElementId")
                    val stateIdentifier = json.getString("stateIdentifier")
                    val timing = json.getString("timing")
                    val breakpointStatus = json.getBoolean("breakpointStatus")
                    if (timing == "entry") {
                        entryBreakpoints.getOrPut(modelElementId) { HashMap() } [stateIdentifier] = breakpointStatus
                        return "Breakpoint override has ${if (breakpointStatus) "added" else "removed"} a breakpoint on state $stateIdentifier entry in statemachine attached to $modelElementId"
                    }
                    if (timing == "exit") {
                        exitBreakpoints.getOrPut(modelElementId) { HashMap() } [stateIdentifier] = breakpointStatus
                        return "Breakpoint override has ${if (breakpointStatus) "added" else "removed"} a breakpoint on state $stateIdentifier exit in statemachine attached to $modelElementId"
                    }
                    "Breakpoint override could not be added to state $stateIdentifier in statemachine attached to $modelElementId, invalid timing $timing"
                }
                "addTransitionOverride" -> {
                    val modelElementId = json.getString("modelElementId")
                    val transitionIdentifier = json.getString("transitionIdentifier")
                    val timing = json.getString("timing")
                    val breakpointStatus = json.getBoolean("breakpointStatus")
                    if (timing == "start") {
                        startBreakpoints.getOrPut(modelElementId) { HashMap() } [transitionIdentifier] = breakpointStatus
                        return "Breakpoint override has ${if (breakpointStatus) "added" else "removed"} a breakpoint on transition $transitionIdentifier start in statemachine attached to $modelElementId"
                    }
                    if (timing == "end") {
                        endBreakpoints.getOrPut(modelElementId) { HashMap() } [transitionIdentifier] = breakpointStatus
                        return "Breakpoint override has ${if (breakpointStatus) "added" else "removed"} a breakpoint on transition $transitionIdentifier end in statemachine attached to $modelElementId"
                    }
                    "Breakpoint override could not be added to transition $transitionIdentifier in statemachine attached to $modelElementId, invalid timing $timing"
                }
                "removeStateOverride" -> {
                    val modelElementId = json.getString("modelElementId")
                    val stateIdentifier = json.getString("stateIdentifier")
                    val timing = json.getString("timing")
                    if (timing == "entry") {
                        entryBreakpoints[modelElementId]?.let {
                            it.remove(stateIdentifier)
                            if (it.isEmpty()) entryBreakpoints.remove(modelElementId)
                        }
                        return "Breakpoint override on state $stateIdentifier entry has been removed in statemachine attached to $modelElementId"
                    }
                    if (timing == "exit") {
                        exitBreakpoints[modelElementId]?.let {
                            it.remove(stateIdentifier)
                            if (it.isEmpty()) exitBreakpoints.remove(modelElementId)
                        }
                        return "Breakpoint override on state $stateIdentifier exit has been removed in statemachine attached to $modelElementId"
                    }
                    "Breakpoint override could not be removed from state $stateIdentifier in statemachine attached to $modelElementId, invalid timing $timing"
                }
                "removeTransitionOverride" -> {
                    val modelElementId = json.getString("modelElementId")
                    val transitionIdentifier = json.getString("transitionIdentifier")
                    val timing = json.getString("timing")
                    if (timing == "start") {
                        startBreakpoints[modelElementId]?.let {
                            it.remove(transitionIdentifier)
                            if (it.isEmpty()) startBreakpoints.remove(modelElementId)
                        }
                        return "Breakpoint override on transition $transitionIdentifier start has been removed in statemachine attached to $modelElementId"
                    }
                    if (timing == "end") {
                        endBreakpoints[modelElementId]?.let {
                            it.remove(transitionIdentifier)
                            if (it.isEmpty()) endBreakpoints.remove(modelElementId)
                        }
                        return "Breakpoint override on transition $transitionIdentifier end has been removed in statemachine attached to $modelElementId"
                    }
                    "Breakpoint override could not be removed from transition $transitionIdentifier in statemachine attached to $modelElementId, invalid timing $timing"
                }
                else -> "Invalid action: $action"
            }
        } catch (e: Exception) {
            return "Error when parsing the message: ${e.message}"
        }
    }

    /**
     * Evaluates state entry breakpoints before each onEntry block
     */
    fun evalStateEntryBreakpoint(modelElementId: String, state: State) {
        evaluateBreakpoint(entryBreakpoints, modelElementId, state.name, state.entryBreakpoint)
    }

    /**
     * Evaluates state exit breakpoints for normal transitions
     * Performs a prefix match on an old and new state stack to find newly exited states
     */
    fun evalStateExitBreakpointNormal(modelElementId: String, oldStateStack: List<State>, newStateStack: List<State>) {
        val stateSet = mutableListOf<State>()

        stateStackPrefixMatch(oldStateStack, newStateStack, stateSet)

        for (state in stateSet) {
            evaluateBreakpoint(exitBreakpoints, modelElementId, state.name, state.exitBreakpoint)
        }
    }

    /**
     * Evaluates state exit breakpoints for split transitions
     * Performs a prefix match on an old and multiple new state stacks to find newly exited states
     */
    fun evalStateExitBreakpointSplit(modelElementId: String, oldStateStack: List<State>, newStateStackList: List<List<State>>) {
        val stateSet = mutableListOf<State>()

        for (newStateStack in newStateStackList) {
            stateStackPrefixMatch(oldStateStack, newStateStack, stateSet)
        }

        for (state in stateSet) {
            evaluateBreakpoint(exitBreakpoints, modelElementId, state.name, state.exitBreakpoint)
        }
    }

    /**
     * Evaluates state exit breakpoints for join transitions
     * Performs a prefix match on multiple old and a new state stack to find newly exited states
     */
    fun evalStateExitBreakpointJoin(modelElementId: String, oldStateStackList: List<List<State>>, newStateStack: List<State>) {
        val stateSet = mutableListOf<State>()

        for (oldStateStack in oldStateStackList) {
            stateStackPrefixMatch(oldStateStack, newStateStack, stateSet)
        }

        for (state in stateSet) {
            evaluateBreakpoint(exitBreakpoints, modelElementId, state.name, state.exitBreakpoint)
        }
    }

    /**
     * Prefix match of two lists to find differences
     */
    private fun stateStackPrefixMatch(oldStateStack: List<State>, newStateStack: List<State>, out: MutableList<State>) {
        var mismatchIndex = 0

        while (mismatchIndex < oldStateStack.size &&
            mismatchIndex < newStateStack.size &&
            oldStateStack[mismatchIndex] == newStateStack[mismatchIndex]) {
            mismatchIndex++
        }

        //Traverse breakpoints in reverse (innermost is exited first)
        for (i in oldStateStack.size - 1 downTo mismatchIndex) {
            val state = oldStateStack[i]
            if (!out.contains(state)) out.add(state)
        }
    }

    /**
     * Evaluates transition start breakpoints before any transition is applied
     */
    fun evalTransitionStartBreakpoint(modelElementId: String, transition: TransitionLike) {
        evaluateBreakpoint(startBreakpoints, modelElementId, transition.toString(), transition.startBreakpoint)
    }

    /**
     * Evaluates transition end breakpoints after any transition has been applied
     */
    fun evalTransitionEndBreakpoint(modelElementId: String, transition: TransitionLike) {
        evaluateBreakpoint(endBreakpoints, modelElementId, transition.toString(), transition.endBreakpoint)
    }

    /**
     * Fetches custom override rules from the corresponding map
     * Uses a default if no custom rule is present
     */
    private fun evaluateBreakpoint(overrideMap: MutableMap<String, MutableMap<String, Boolean>>, modelElementId: String, targetIdentifier: String, defaultBreakpointStatus: Boolean) {
        var breakpointStatus: Boolean? = null
        overrideMap[modelElementId]?.let {
            it[targetIdentifier]?.let { status ->
                breakpointStatus = status
            }
        }
        if (breakpointStatus == null) {
            breakpointStatus = defaultBreakpointStatus
        }
        if (breakpointStatus) {
            tickByTickFeature.onMessage("pause")
            tickByTickFeature.evalPausedState()
        }
    }
}

@AutoService(FeatureProvider::class)
class BreakpointProvider : FeatureProvider {
    override val featureDependencies: Set<KClass<out Feature>> = hashSetOf(TickByTickFeature::class)
    override val registryClass: KClass<out Feature> = BreakpointFeature::class
    override val registryName: String = "Breakpoint"

    override fun createFeature(manager: FeatureManager): Feature {
        return BreakpointFeature(manager.getActiveFeature<TickByTickFeature>())
    }
}