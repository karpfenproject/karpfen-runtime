package io.karpfen.features.language

import io.karpfen.Engine
import io.karpfen.features.createEngineAndRunEngineCycle
import io.karpfen.features.getLastEnteredState
import io.karpfen.features.globalMetamodelId
import io.karpfen.features.globalModelElementId
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.language.HistoryFeature
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import states.History
import states.State
import states.actions.ActionBlock
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HistoryTest {

    lateinit var manager: FeatureManager
    lateinit var lastVisitedSubstates: MutableMap<String, MutableMap<String, String>>

    @Suppress("UNCHECKED_CAST")
    fun setup(): HistoryFeature {
        manager = FeatureManager()
        manager.requestFeatureActivation(HistoryFeature::class)
        val lastVisitedSubstatesField = HistoryFeature::class.java.getDeclaredField("lastVisitedSubstates")
        lastVisitedSubstatesField.setAccessible(true)
        val feature = manager.getActiveFeature<HistoryFeature>()
        lastVisitedSubstates = lastVisitedSubstatesField.get(feature) as MutableMap<String, MutableMap<String, String>>
        return feature
    }

    fun assertStateReached(stateName: String, engine: Engine) {
        Thread.sleep(100)
        assertEquals(stateName, getLastEnteredState(engine.traceLogger!!))
    }

    fun assertLastVisitedState(state: State, expected: String?) {
        val lastVisitedSubstate = lastVisitedSubstates[globalModelElementId]?.get(state.name)
        assertEquals(expected, lastVisitedSubstate)
    }

    fun getStateMachineDefinition(type: String): String {
        return "STATEMACHINE ATTACHED TO \"$globalMetamodelId\" {\n" +
                "\tSTATES {\n" +
                "\t\tINITIAL STATE \"start\" {}\n" +
                "\t\t${if (type == "shallow") "SHALLOW HISTORY " else ""}${if (type == "deep") "DEEP HISTORY " else ""}STATE \"outer\" {\n" +
                "\t\t\tSTATE \"middle\" {\n" +
                "\t\t\t\tSTATE \"inner\" {}\n" +
                "\t\t\t}\n" +
                "\t\t}\n" +
                "\t\tSTATE \"lock\" {\n" +
                "\t\t\tENTRY {\n" +
                "\t\t\t\tSET(\"state\", \"false\")\n" +
                "\t\t\t}\n" +
                "\t\t}\n" +
                "\t}\n" +
                "\tTRANSITIONS {\n" +
                "\t\tTRANSITION \"start\" -> \"outer\" {}" +
                "\t\tTRANSITION \"outer\" -> \"middle\" {\n" +
                "\t\t\tCONDITION {\n" +
                "\t\t\t\tEVAL { return $(state) }\n" +
                "\t\t\t}\n" +
                "\t\t}\n" +
                "\t\tTRANSITION \"middle\" -> \"inner\" {\n" +
                "\t\t\tCONDITION {\n" +
                "\t\t\t\tEVAL { return $(state) }\n" +
                "\t\t\t}\n" +
                "\t\t}\n" +
                "\t\tTRANSITION \"inner\" -> \"lock\" {\n" +
                "\t\t\tCONDITION {\n" +
                "\t\t\t\tEVAL { return $(state) }\n" +
                "\t\t\t}\n" +
                "\t\t}\n" +
                "\t\tTRANSITION \"lock\" -> \"outer\" {}\n" +
                "\t}\n" +
                "}".trimIndent()
    }

    @Test
    fun testHistoryActivation() {
        assertDoesNotThrow { setup() }
    }

    @Test
    fun testUpdateHistory() {
        val historyFeature = setup()

        //No history test
        var stateStack = listOf(
            State("outerState", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.NONE),
            State("middleState", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.NONE),
            State("innerState", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.NONE)
        )

        historyFeature.updateHistory(globalModelElementId, stateStack)

        for (state in stateStack) {
            assertLastVisitedState(state, null)
        }

        //Shallow history test
        stateStack = listOf(
            State("outerState", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.SHALLOW),
            State("middleState", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.SHALLOW),
            State("innerState", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.SHALLOW)
        )

        historyFeature.updateHistory(globalModelElementId, stateStack)

        assertLastVisitedState(stateStack.first(), null)
        assertLastVisitedState(stateStack[1], "innerState")
        assertLastVisitedState(stateStack.first(), null)

        //Deep history test
        stateStack = listOf(
            State("outerState", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.DEEP),
            State("middleState", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.DEEP),
            State("innerState", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.DEEP)
        )

        historyFeature.updateHistory(globalModelElementId, stateStack)

        assertLastVisitedState(stateStack.first(), "innerState")
        assertLastVisitedState(stateStack[1], "innerState")
        assertLastVisitedState(stateStack.last(), null)

    }

    @Test
    fun testCreateTransitionOrNull() {
        val historyFeature = setup()

        lastVisitedSubstates[globalModelElementId] = mutableMapOf(Pair("state", "otherState"))

        //No history test
        var state = State("state", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.NONE)
        assertNull(historyFeature.createTransitionOrNull(globalModelElementId, state))

        //With History test
        state = State("state", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.SHALLOW)
        var transition = assertNotNull(historyFeature.createTransitionOrNull(globalModelElementId, state))
        assertEquals("state", transition.fromState)
        assertEquals("otherState", transition.toState)

        state = State("state", mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), history = History.DEEP)
        transition = assertNotNull(historyFeature.createTransitionOrNull(globalModelElementId, state))
        assertEquals("state", transition.fromState)
        assertEquals("otherState", transition.toState)
    }

    @Test
    fun testResetHistory() {
        val historyFeature = setup()

        lastVisitedSubstates[globalModelElementId] = mutableMapOf(Pair("state", "otherState"))

        historyFeature.resetHistory()

        assertEquals(0, lastVisitedSubstates.size)
    }

    @Test
    fun testHistoryIntegration() {
        setup()

        //test default execution, should be stuck in first
        assertStateReached("outer", createEngineAndRunEngineCycle(getStateMachineDefinition(""), manager, true))

        //test shallow history on first, should be stuck in firstTop
        assertStateReached("middle", createEngineAndRunEngineCycle(getStateMachineDefinition("shallow"), manager, true))

        //test deep history on first, should be stuck in firstBottom
        assertStateReached("inner", createEngineAndRunEngineCycle(getStateMachineDefinition("deep"), manager, true))
    }
}