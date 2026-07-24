package io.karpfen.features.language

import io.karpfen.Engine
import io.karpfen.features.*
import io.karpfen.io.karpfen.exec.Branch
import io.karpfen.io.karpfen.exec.SMContext
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.language.TickLimitFeature
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import states.State
import kotlin.test.*

class TickLimitTest {

    lateinit var tickField: java.lang.reflect.Field
    lateinit var manager: FeatureManager

    fun setup(): TickLimitFeature {
        manager = FeatureManager()
        manager.requestFeatureActivation(TickLimitFeature::class)
        tickField = TickLimitFeature::class.java.getDeclaredField("tickMap")
        tickField.setAccessible(true)
        return manager.getActiveFeature<TickLimitFeature>()
    }

    @Suppress("UNCHECKED_CAST")
    fun simulateTicks(tickAmount: Long, stateStack: List<String>, tickLimitFeature: TickLimitFeature) {
        val ctx = SMContext(
            modelElementId = globalModelElementId,
            stateMachine = mockk(),
            smQueryHelper = mockk(),
            transitionProcessor = mockk(),
            actionProcessor = mockk(),
            eventBus = mockk(),
            branchIdBase = "",
            branches = mutableListOf(Branch(
                eventProcessor = mockk(),
                stateStack = stateStack,
                notEnteredSubstack = mutableListOf()
            ))
        )

        val previousTickMap = tickField.get(tickLimitFeature) as MutableMap<String, MutableMap<String, Long>>

        for (i in 0 until tickAmount) {
            tickLimitFeature.incrementTick(listOf(ctx))
        }

        val currentTickMap = tickField.get(tickLimitFeature) as MutableMap<String, MutableMap<String, Long>>

        for (stateName in stateStack) {
            assertEquals(previousTickMap.getOrDefault(globalModelElementId, mutableMapOf()).getOrDefault(stateName, 0) + tickAmount, currentTickMap[globalModelElementId]?.get(stateName))
        }

        assertEquals(stateStack.size, currentTickMap[globalModelElementId]?.size)
    }

    @Test
    fun testTickLimitActivation() {
        assertDoesNotThrow { setup() }
    }

    @Test
    fun testIncrementTick() {
        val tickLimitFeature = setup()

        simulateTicks(10, listOf("state1", "state2", "state3"), tickLimitFeature)

        simulateTicks(3, listOf("state4"), tickLimitFeature)
    }

    @Test
    fun testIsTransitionBlocked() {
        val tickLimitFeature = setup()

        val stateWithoutDelay = State("stateWithoutDelay", mockk(), mockk(), mutableListOf())
        val stateLowDelay = State("stateLowDelay", mockk(), mockk(), mutableListOf(), delay = 10)
        val stateHighDelay = State("stateHighDelay", mockk(), mockk(), mutableListOf(), delay = 20)

        val stateStack = listOf(stateWithoutDelay, stateLowDelay, stateHighDelay)
        simulateTicks(10, stateStack.map { it.name }, tickLimitFeature)

        assertTrue(tickLimitFeature.isTransitionBlocked(globalModelElementId, stateStack))
        assertTrue(tickLimitFeature.isTransitionBlocked(globalModelElementId, stateStack.reversed()))

        assertFalse(tickLimitFeature.isTransitionBlocked(globalModelElementId, listOf(stateWithoutDelay)))
        assertTrue(tickLimitFeature.isTransitionBlocked(globalModelElementId, listOf(stateLowDelay)))
        assertTrue(tickLimitFeature.isTransitionBlocked(globalModelElementId, listOf(stateHighDelay)))

        simulateTicks(10, stateStack.map { it.name }, tickLimitFeature)

        assertTrue(tickLimitFeature.isTransitionBlocked(globalModelElementId, stateStack))
        assertTrue(tickLimitFeature.isTransitionBlocked(globalModelElementId, stateStack.reversed()))

        assertFalse(tickLimitFeature.isTransitionBlocked(globalModelElementId, listOf(stateWithoutDelay)))
        assertFalse(tickLimitFeature.isTransitionBlocked(globalModelElementId, listOf(stateLowDelay)))
        assertTrue(tickLimitFeature.isTransitionBlocked(globalModelElementId, listOf(stateHighDelay)))

        simulateTicks(1, stateStack.map { it.name }, tickLimitFeature)

        assertFalse(tickLimitFeature.isTransitionBlocked(globalModelElementId, stateStack))
        assertFalse(tickLimitFeature.isTransitionBlocked(globalModelElementId, stateStack.reversed()))

        assertFalse(tickLimitFeature.isTransitionBlocked(globalModelElementId, listOf(stateWithoutDelay)))
        assertFalse(tickLimitFeature.isTransitionBlocked(globalModelElementId, listOf(stateLowDelay)))
        assertFalse(tickLimitFeature.isTransitionBlocked(globalModelElementId, listOf(stateHighDelay)))
    }

    @Test
    fun testEvalNecessaryTransition() {
        val tickLimitFeature = setup()

        val stateWithoutTimeout = State("stateWithoutTimeout", mockk(), mockk(), mutableListOf())
        val stateLowTimeout = State("stateLowTimeout", mockk(), mockk(), mutableListOf(), timeout = 10, timeoutTo = "destinationState")
        val stateHighTimeout = State("stateHighTimeout", mockk(), mockk(), mutableListOf(), timeout = 20, timeoutTo = "destinationState")

        val stateStack = listOf(stateWithoutTimeout, stateLowTimeout, stateHighTimeout)
        simulateTicks(10, stateStack.map { it.name }, tickLimitFeature)

        assertNull(tickLimitFeature.evalNecessaryTransition(globalModelElementId, stateStack))
        assertNull(tickLimitFeature.evalNecessaryTransition(globalModelElementId, stateStack.reversed()))

        simulateTicks(1, stateStack.map { it.name }, tickLimitFeature)

        var transition = assertNotNull(tickLimitFeature.evalNecessaryTransition(globalModelElementId, stateStack))
        assertEquals("stateLowTimeout", transition.fromState)
        assertEquals("destinationState", transition.toState)
        transition = assertNotNull(tickLimitFeature.evalNecessaryTransition(globalModelElementId, stateStack.reversed()))
        assertEquals("stateLowTimeout", transition.fromState)
        assertEquals("destinationState", transition.toState)

        assertNull(tickLimitFeature.evalNecessaryTransition(globalModelElementId, listOf(stateWithoutTimeout)))
        transition = assertNotNull(tickLimitFeature.evalNecessaryTransition(globalModelElementId, listOf(stateLowTimeout)))
        assertEquals("stateLowTimeout", transition.fromState)
        assertEquals("destinationState", transition.toState)
        assertNull(tickLimitFeature.evalNecessaryTransition(globalModelElementId, listOf(stateHighTimeout)))
    }

    @Nested
    inner class IntegrationTests {

        fun assertEndReached(engine: Engine) {
            assertEquals("destination", getLastEnteredState(engine.traceLogger!!))
            stopEngine(engine)
        }

        fun getStateMachineDefinition(outerStateLimit: String, innerStateLimit: String, transition: String): String {
            return "STATEMACHINE ATTACHED TO \"$globalMetamodelId\" {\n" +
                    "\tSTATES {\n" +
                    "\t\tINITIAL STATE \"outerState\" {\n" +
                    "\t\t\tTICK LIMIT {\n" +
                    "\t\t\t\t$outerStateLimit\n" +
                    "\t\t\t}\n" +
                    "\t\t\tSTATE \"innerState\" {\n" +
                    "\t\t\t\tTICK LIMIT {\n" +
                    "\t\t\t\t\t$innerStateLimit\n" +
                    "\t\t\t\t}\n" +
                    "\t\t\t}\n" +
                    "\t\t}\n" +
                    "\t\tSTATE \"destination\" {}\n" +
                    "\t}\n" +
                    "\tTRANSITIONS {$transition}\n" +
                    "}".trimIndent()
        }

        @Test
        fun testDefaultBehaviour() {
            setup()

            assertStateReached("innerState", createEngineAndRunEngineCycle(getStateMachineDefinition("", "", ""), manager, true))
            assertEndReached(createEngineAndRunEngineCycle(getStateMachineDefinition("", "", "TRANSITION \"innerState\" -> \"destination\" {}"), manager, true))
        }

        @Test
        fun testDelayIntegration() {
            setup()

            //test inner state delay before outer
            var engine = createEngineAndRunEngineCycle(getStateMachineDefinition("DELAY: 5 TICKS", "DELAY: 3 TICKS", "TRANSITION \"innerState\" -> \"destination\" {}"), manager, true)
            assertEndReached(engine)
            var details = assertNotNull(getLastFiredTransitionDetails(engine.traceLogger!!))
            assertEquals("innerState", details["from"])
            assertEquals("destination", details["to"])
            //test that transition happened at tick 6
            assertTransitionHappenedAt(engine, 6, "outerState,innerState","destination")

            //test outer state delay before inner
            engine = createEngineAndRunEngineCycle(getStateMachineDefinition("DELAY: 3 TICKS", "DELAY: 5 TICKS", "TRANSITION \"innerState\" -> \"destination\" {}"), manager, true)
            assertEndReached(engine)
            details = assertNotNull(getLastFiredTransitionDetails(engine.traceLogger!!))
            assertEquals("innerState", details["from"])
            assertEquals("destination", details["to"])
            //test that transition happened at tick 6
            assertTransitionHappenedAt(engine, 6, "outerState,innerState","destination")
        }

        @Test
        fun testTimeoutIntegration() {
            setup()

            //test inner state timeout before outer
            var engine = createEngineAndRunEngineCycle(getStateMachineDefinition("TIMEOUT: AFTER 10 TICKS TRANSITION TO \"destination\"", "TIMEOUT: AFTER 5 TICKS TRANSITION TO \"destination\"", ""), manager, true)
            assertEndReached(engine)
            var details = assertNotNull(getLastFiredTransitionDetails(engine.traceLogger!!))
            assertEquals("innerState", details["from"])
            assertEquals("destination", details["to"])
            //test that transition happened at tick 6
            assertTransitionHappenedAt(engine, 6, "outerState,innerState","destination")

            //test outer state timeout before inner
            engine = createEngineAndRunEngineCycle(getStateMachineDefinition("TIMEOUT: AFTER 5 TICKS TRANSITION TO \"destination\"", "TIMEOUT: AFTER 10 TICKS TRANSITION TO \"destination\"", ""), manager, true)
            assertEndReached(engine)
            details = assertNotNull(getLastFiredTransitionDetails(engine.traceLogger!!))
            assertEquals("outerState", details["from"])
            assertEquals("destination", details["to"])
            //test that transition happened at tick 6
            assertTransitionHappenedAt(engine, 6, "outerState,innerState","destination")
        }
    }
}