package io.karpfen.features.runtime

import io.karpfen.Engine
import io.karpfen.EngineTraceLogger.TraceEventType
import io.karpfen.features.*
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.runtime.BreakpointFeature
import io.karpfen.io.karpfen.features.runtime.TickByTickFeature
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import states.State
import states.Transition
import states.actions.ActionBlock
import states.conditions.Condition
import states.conditions.ConditionType
import kotlin.test.*

class BreakpointTest {

    val stateIdentifier1 = "stateIdentifier1"
    val stateIdentifier2 = "stateIdentifier2"
    val transitionIdentifier1 = "transitionIdentifier1"
    val transitionIdentifier2 = "transitionIdentifier2"

    var isPaused = false

    lateinit var manager: FeatureManager

    fun setup(): BreakpointFeature {
        manager = FeatureManager()
        manager.requestFeatureActivation(BreakpointFeature::class)
        return manager.getActiveFeature<BreakpointFeature>()
    }

    fun replaceTickByTickFeature(breakpointFeature: BreakpointFeature) {
        isPaused = false
        val tickByTickFeature = mockk<TickByTickFeature>(relaxed = true)
        every {tickByTickFeature.onMessage("pause")} answers {
            isPaused = true
            ""
        }
        val tickByTickField = breakpointFeature::class.java.getDeclaredField("tickByTickFeature")
        tickByTickField.setAccessible(true)
        tickByTickField.set(breakpointFeature, tickByTickFeature)
    }

    //Returns null if no entry in map, otherwise returns breakpoint status
    fun evalOverrideInMap(map: MutableMap<String, MutableMap<String, Boolean>>, identifier: String): Boolean? {
        return map[globalModelElementId]?.let { it[identifier] }
    }

    @Test
    fun testBreakpointActivation() {
        val breakpointFeature = assertDoesNotThrow { setup() }

        val managerTickByTickFeature = assertDoesNotThrow { manager.getActiveFeature<TickByTickFeature>() }

        val tickByTickField = BreakpointFeature::class.java.getDeclaredField("tickByTickFeature")
        tickByTickField.setAccessible(true)
        val tickByTickFeature = tickByTickField.get(breakpointFeature)

        assertEquals(managerTickByTickFeature, tickByTickFeature)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testBreakpointMessaging() {
        val breakpointFeature = setup()

        val entryField = BreakpointFeature::class.java.getDeclaredField("entryBreakpoints")
        val exitField = BreakpointFeature::class.java.getDeclaredField("exitBreakpoints")
        val startField = BreakpointFeature::class.java.getDeclaredField("startBreakpoints")
        val endField = BreakpointFeature::class.java.getDeclaredField("endBreakpoints")
        entryField.setAccessible(true)
        exitField.setAccessible(true)
        startField.setAccessible(true)
        endField.setAccessible(true)
        val entryMap = entryField.get(breakpointFeature) as MutableMap<String, MutableMap<String, Boolean>>
        val exitMap = exitField.get(breakpointFeature) as MutableMap<String, MutableMap<String, Boolean>>
        val startMap = startField.get(breakpointFeature) as MutableMap<String, MutableMap<String, Boolean>>
        val endMap = endField.get(breakpointFeature) as MutableMap<String, MutableMap<String, Boolean>>

        //invalid message
        assertTrue(breakpointFeature.onMessage("invalid").startsWith("Error when parsing the message: "))

        //invalid action
        assertTrue(breakpointFeature.onMessage("{action: \"invalid\"}").startsWith("Invalid action: "))

        //getOverrides empty
        assertEquals("{Entry Breakpoints={}, Exit Breakpoints={}, Start Breakpoints={}, End Breakpoints={}}", breakpointFeature.onMessage("{action: \"getOverrides\"}"))

        
        //addStateOverride
        //invalid timing
        assertEquals("Breakpoint override could not be added to state $stateIdentifier1 in statemachine attached to $globalModelElementId, invalid timing invalid",
            breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"invalid\", breakpointStatus: false}")
        )
        
        //entry
        assertEquals("Breakpoint override has added a breakpoint on state $stateIdentifier1 entry in statemachine attached to $globalModelElementId",
                    breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"entry\", breakpointStatus: true}"))
        assertTrue(assertNotNull(evalOverrideInMap(entryMap,stateIdentifier1)))
        
        assertEquals("Breakpoint override has removed a breakpoint on state $stateIdentifier2 entry in statemachine attached to $globalModelElementId",
                    breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier2, timing: \"entry\", breakpointStatus: false}"))
        assertFalse(assertNotNull(evalOverrideInMap(entryMap,stateIdentifier2)))
        
        //exit
        assertEquals("Breakpoint override has added a breakpoint on state $stateIdentifier1 exit in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"exit\", breakpointStatus: true}"))
        assertTrue(assertNotNull(evalOverrideInMap(exitMap,stateIdentifier1)))
        
        assertEquals("Breakpoint override has removed a breakpoint on state $stateIdentifier2 exit in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier2, timing: \"exit\", breakpointStatus: false}"))
        assertFalse(assertNotNull(evalOverrideInMap(exitMap,stateIdentifier2)))


        //addTransitionOverride
        //invalid timing
        assertEquals("Breakpoint override could not be added to transition $transitionIdentifier1 in statemachine attached to $globalModelElementId, invalid timing invalid",
            breakpointFeature.onMessage("{action: \"addTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: $transitionIdentifier1, timing: \"invalid\", breakpointStatus: false}")
        )

        //start
        assertEquals("Breakpoint override has added a breakpoint on transition $transitionIdentifier1 start in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"addTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: $transitionIdentifier1, timing: \"start\", breakpointStatus: true}"))
        assertTrue(assertNotNull(evalOverrideInMap(startMap,transitionIdentifier1)))
        
        assertEquals("Breakpoint override has removed a breakpoint on transition $transitionIdentifier2 start in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"addTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: $transitionIdentifier2, timing: \"start\", breakpointStatus: false}"))
        assertFalse(assertNotNull(evalOverrideInMap(startMap,transitionIdentifier2)))
        
        //end
        assertEquals("Breakpoint override has added a breakpoint on transition $transitionIdentifier1 end in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"addTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: $transitionIdentifier1, timing: \"end\", breakpointStatus: true}"))
        assertTrue(assertNotNull(evalOverrideInMap(endMap,transitionIdentifier1)))
        assertEquals("Breakpoint override has removed a breakpoint on transition $transitionIdentifier2 end in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"addTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: $transitionIdentifier2, timing: \"end\", breakpointStatus: false}"))
        assertFalse(assertNotNull(evalOverrideInMap(endMap,transitionIdentifier2)))

        //getOverrides full
        assertEquals("{Entry Breakpoints={$globalModelElementId={$stateIdentifier1=true, $stateIdentifier2=false}}, Exit Breakpoints={$globalModelElementId={$stateIdentifier1=true, $stateIdentifier2=false}}, Start Breakpoints={$globalModelElementId={$transitionIdentifier1=true, $transitionIdentifier2=false}}, End Breakpoints={$globalModelElementId={$transitionIdentifier1=true, $transitionIdentifier2=false}}}",
            breakpointFeature.onMessage("{action: \"getOverrides\"}")
        )

        
        //removeStateOverride
        //invalid timing
        assertEquals("Breakpoint override could not be removed from state $stateIdentifier1 in statemachine attached to $globalModelElementId, invalid timing invalid",
            breakpointFeature.onMessage("{action: \"removeStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"invalid\"}")
        )

        //entry
        assertEquals("Breakpoint override on state $stateIdentifier1 entry has been removed in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"removeStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"entry\"}"))
        assertNull(evalOverrideInMap(entryMap, stateIdentifier1))

        assertEquals("Breakpoint override on state $stateIdentifier2 entry has been removed in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"removeStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier2, timing: \"entry\"}"))
        assertNull(evalOverrideInMap(entryMap, stateIdentifier2))

        //exit
        assertEquals("Breakpoint override on state $stateIdentifier1 exit has been removed in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"removeStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"exit\"}"))
        assertNull(evalOverrideInMap(exitMap, stateIdentifier1))

        assertEquals("Breakpoint override on state $stateIdentifier2 exit has been removed in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"removeStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier2, timing: \"exit\"}"))
        assertNull(evalOverrideInMap(exitMap, stateIdentifier2))

        
        //removeTransitionOverride
        //invalid timing
        assertEquals("Breakpoint override could not be removed from transition $transitionIdentifier1 in statemachine attached to $globalModelElementId, invalid timing invalid",
            breakpointFeature.onMessage("{action: \"removeTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: $transitionIdentifier1, timing: \"invalid\"}")
        )

        //start
        assertEquals("Breakpoint override on transition $transitionIdentifier1 start has been removed in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"removeTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: $transitionIdentifier1, timing: \"start\"}"))
        assertNull(evalOverrideInMap(startMap, transitionIdentifier1))

        assertEquals("Breakpoint override on transition $transitionIdentifier2 start has been removed in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"removeTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: $transitionIdentifier2, timing: \"start\"}"))
        assertNull(evalOverrideInMap(startMap, transitionIdentifier2))

        //end
        assertEquals("Breakpoint override on transition $transitionIdentifier1 end has been removed in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"removeTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: $transitionIdentifier1, timing: \"end\"}"))
        assertNull(evalOverrideInMap(endMap, transitionIdentifier1))

        assertEquals("Breakpoint override on transition $transitionIdentifier2 end has been removed in statemachine attached to $globalModelElementId",
            breakpointFeature.onMessage("{action: \"removeTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: $transitionIdentifier2, timing: \"end\"}"))
        assertNull(evalOverrideInMap(endMap, transitionIdentifier2))

        //getOverrides empty again
        assertEquals("{Entry Breakpoints={}, Exit Breakpoints={}, Start Breakpoints={}, End Breakpoints={}}", breakpointFeature.onMessage("{action: \"getOverrides\"}"))
    }

    @Test
    fun testEvalStateEntryBreakpoint() {
        val breakpointFeature = setup()
        replaceTickByTickFeature(breakpointFeature)

        //Check DSL Breakpoint off
        var state = State(stateIdentifier1, mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf())
        breakpointFeature.evalStateEntryBreakpoint(globalModelElementId, state)
        assertFalse(isPaused)

        //Check override
        breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"entry\", breakpointStatus: true}")
        breakpointFeature.evalStateEntryBreakpoint(globalModelElementId, state)
        assertTrue(isPaused)
        isPaused = false

        breakpointFeature.onMessage("{action: \"removeStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"entry\"}")

        //Check DSL breakpoint on
        state = State(stateIdentifier1, mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), entryBreakpoint = true)
        breakpointFeature.evalStateEntryBreakpoint(globalModelElementId, state)
        assertTrue(isPaused)
        isPaused = false

        //Check override
        breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"entry\", breakpointStatus: false}")
        breakpointFeature.evalStateEntryBreakpoint(globalModelElementId, state)
        assertFalse(isPaused)
    }

    @Test
    fun testEvalStateExitBreakpoint() {
        val breakpointFeature = setup()
        replaceTickByTickFeature(breakpointFeature)

        val parentState = mockk<State>(relaxed = true)
        val parallelState = mockk<State>(relaxed = true)

        //Normal
        //Check DSL Breakpoint off
        var state = State(stateIdentifier1, mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf())
        breakpointFeature.evalStateExitBreakpointNormal(globalModelElementId, listOf(parentState, state), listOf(parentState, parallelState))
        assertFalse(isPaused)

        //Check override
        breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"exit\", breakpointStatus: true}")
        breakpointFeature.evalStateExitBreakpointNormal(globalModelElementId, listOf(parentState, state), listOf(parentState, parallelState))
        assertTrue(isPaused)
        isPaused = false

        breakpointFeature.onMessage("{action: \"removeStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"exit\"}")

        //Check DSL breakpoint on
        state = State(stateIdentifier1, mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), exitBreakpoint = true)
        breakpointFeature.evalStateExitBreakpointNormal(globalModelElementId, listOf(parentState, state), listOf(parentState, parallelState))
        assertTrue(isPaused)
        isPaused = false

        //Check override
        breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"exit\", breakpointStatus: false}")
        breakpointFeature.evalStateExitBreakpointNormal(globalModelElementId, listOf(parentState, state), listOf(parentState, parallelState))
        assertFalse(isPaused)

        //Split
        //Check DSL Breakpoint off
        state = State(stateIdentifier1, mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf())
        breakpointFeature.evalStateExitBreakpointSplit(globalModelElementId, listOf(parentState, state), listOf(listOf(parentState, parallelState)))
        assertFalse(isPaused)

        //Check override
        breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"exit\", breakpointStatus: true}")
        breakpointFeature.evalStateExitBreakpointSplit(globalModelElementId, listOf(parentState, state), listOf(listOf(parentState, parallelState)))
        assertTrue(isPaused)
        isPaused = false

        breakpointFeature.onMessage("{action: \"removeStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"exit\"}")

        //Check DSL breakpoint on
        state = State(stateIdentifier1, mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), exitBreakpoint = true)
        breakpointFeature.evalStateExitBreakpointSplit(globalModelElementId, listOf(parentState, state), listOf(listOf(parentState, parallelState)))
        assertTrue(isPaused)
        isPaused = false

        //Check override
        breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"exit\", breakpointStatus: false}")
        breakpointFeature.evalStateExitBreakpointSplit(globalModelElementId, listOf(parentState, state), listOf(listOf(parentState, parallelState)))
        assertFalse(isPaused)

        //Join
        //Check DSL Breakpoint off
        state = State(stateIdentifier1, mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf())
        breakpointFeature.evalStateExitBreakpointJoin(globalModelElementId, listOf(listOf(parentState, state)), listOf(parentState, parallelState))
        assertFalse(isPaused)

        //Check override
        breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"exit\", breakpointStatus: true}")
        breakpointFeature.evalStateExitBreakpointJoin(globalModelElementId, listOf(listOf(parentState, state)), listOf(parentState, parallelState))
        assertTrue(isPaused)
        isPaused = false

        breakpointFeature.onMessage("{action: \"removeStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"exit\"}")

        //Check DSL breakpoint on
        state = State(stateIdentifier1, mockk<ActionBlock>(), mockk<ActionBlock>(), mutableListOf(), exitBreakpoint = true)
        breakpointFeature.evalStateExitBreakpointJoin(globalModelElementId, listOf(listOf(parentState, state)), listOf(parentState, parallelState))
        assertTrue(isPaused)
        isPaused = false

        //Check override
        breakpointFeature.onMessage("{action: \"addStateOverride\", modelElementId: $globalModelElementId, stateIdentifier: $stateIdentifier1, timing: \"exit\", breakpointStatus: false}")
        breakpointFeature.evalStateExitBreakpointJoin(globalModelElementId, listOf(listOf(parentState, state)), listOf(parentState, parallelState))
        assertFalse(isPaused)
    }

    @Test
    fun testEvalTransitionStartBreakpoint() {
        val breakpointFeature = setup()
        replaceTickByTickFeature(breakpointFeature)

        //Check DSL Breakpoint off
        var transition = Transition(stateIdentifier1, stateIdentifier2, true, false, false, Condition(ConditionType.EVAL))
        breakpointFeature.evalTransitionStartBreakpoint(globalModelElementId, transition)
        assertFalse(isPaused)

        //Check override
        breakpointFeature.onMessage("{action: \"addTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: \"$transition\", timing: \"start\", breakpointStatus: true}")
        breakpointFeature.evalTransitionStartBreakpoint(globalModelElementId, transition)
        assertTrue(isPaused)
        isPaused = false

        breakpointFeature.onMessage("{action: \"removeTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: \"$transition\", timing: \"start\"}")

        //Check DSL breakpoint on
        transition = Transition(stateIdentifier1, stateIdentifier2, true, true, false, Condition(ConditionType.EVAL))
        breakpointFeature.evalTransitionStartBreakpoint(globalModelElementId, transition)
        assertTrue(isPaused)
        isPaused = false

        //Check override
        breakpointFeature.onMessage("{action: \"addTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: \"$transition\", timing: \"start\", breakpointStatus: false}")
        breakpointFeature.evalTransitionStartBreakpoint(globalModelElementId, transition)
        assertFalse(isPaused)
    }

    @Test
    fun testEvalTransitionEndBreakpoint() {
        val breakpointFeature = setup()
        replaceTickByTickFeature(breakpointFeature)


        //Check DSL Breakpoint off
        var transition = Transition(stateIdentifier1, stateIdentifier2, true, false, false, Condition(ConditionType.EVAL))
        breakpointFeature.evalTransitionEndBreakpoint(globalModelElementId, transition)
        assertFalse(isPaused)

        //Check override
        breakpointFeature.onMessage("{action: \"addTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: \"$transition\", timing: \"end\", breakpointStatus: true}")
        breakpointFeature.evalTransitionEndBreakpoint(globalModelElementId, transition)
        assertTrue(isPaused)
        isPaused = false

        breakpointFeature.onMessage("{action: \"removeTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: \"$transition\", timing: \"end\"}")

        //Check DSL breakpoint on
        transition = Transition(stateIdentifier1, stateIdentifier2, true, false, true, Condition(ConditionType.EVAL))
        breakpointFeature.evalTransitionEndBreakpoint(globalModelElementId, transition)
        assertTrue(isPaused)
        isPaused = false

        //Check override
        breakpointFeature.onMessage("{action: \"addTransitionOverride\", modelElementId: $globalModelElementId, transitionIdentifier: \"$transition\", timing: \"end\", breakpointStatus: false}")
        breakpointFeature.evalTransitionEndBreakpoint(globalModelElementId, transition)
        assertFalse(isPaused)
    }

    @Nested
    inner class IntegrationTests {

        fun getStateMachineDefinition(type: String): String {
            return "STATEMACHINE ATTACHED TO \"$globalMetamodelId\" {\n" +
                    "\tSTATES {\n" +
                    "\t\tINITIAL STATE \"first\" {BREAKPOINT {${if (type == "exit") "EXIT" else ""}}}\n" +
                    "\t\tSTATE \"second\" {\n" +
                    "\t\t\tBREAKPOINT {${if (type == "entry") "ENTRY" else ""}${if (type == "exit") "EXIT" else ""}}\n" +
                    "\t\t\tSTATE \"parallelOne\" {BREAKPOINT {${if (type == "exit") "EXIT" else ""}}}\n" +
                    "\t\t\tSTATE \"parallelTwo\" {BREAKPOINT {${if (type == "entry") "ENTRY" else ""}}}\n" +
                    "\t\t\tSTATE \"parallelThree\" {BREAKPOINT {${if (type == "exit") "EXIT" else ""}}}\n" +
                    "\t\t\tSTATE \"parallelFour\" {BREAKPOINT {${if (type == "entry") "ENTRY" else ""}}}\n" +
                    "\t\t}\n" +
                    "\t\tSTATE \"third\" {BREAKPOINT {${if (type == "entry") "ENTRY" else ""}}}\n" +
                    "\t}\n" +
                    "\tTRANSITIONS {\n" +
                    "\t\tSPLIT \"first\" -> \"parallelOne\", \"parallelTwo\" {BREAKPOINT {${if (type == "start") "START" else ""}${if (type == "end") "END" else ""}}}\n" +
                    "\t\tTRANSITION \"parallelOne\" -> \"parallelThree\" {BREAKPOINT {${if (type == "start") "START" else ""}${if (type == "end") "END" else ""}}}\n" +
                    "\t\tTRANSITION \"parallelTwo\" -> \"parallelFour\" {BREAKPOINT {${if (type == "start") "START" else ""}${if (type == "end") "END" else ""}}}\n" +
                    "\t\tJOIN \"parallelThree\", \"parallelFour\" -> \"third\" {BREAKPOINT {${if (type == "start") "START" else ""}${if (type == "end") "END" else ""}}}\n" +
                    "\t}\n" +
                    "}".trimIndent()
        }

        fun assertStateReached(stateName: String, engine: Engine) {
            assertEquals(stateName, getLastEnteredState(engine.traceLogger!!))
            resume()
        }

        fun assertEndReached(engine: Engine) {
            assertEquals("third", getLastEnteredState(engine.traceLogger!!))
            stopEngine(engine)
        }

        fun resume() {
            manager.onMessage(TickByTickFeature::class, "resume")
            Thread.sleep(100)
        }

        @Test
        fun testDefaultBehavior() {
            setup()

            assertEndReached(createEngineAndRunEngineCycle(getStateMachineDefinition(""), manager, true))
        }

        @Test
        fun testEntryBreakpoints() {
            setup()

            val engine = createEngineAndRunEngineCycle(getStateMachineDefinition("entry"), manager, false)
            //stuck on second entry
            assertStateReached("first", engine)
            //stuck on second entry again (parallel branch)
            assertStateReached("parallelOne", engine)
            //stuck on parallelTwo entry
            assertStateReached("second", engine)
            //ParallelFour entry breakpoint triggered in Join, but last entry still on parallelTwo since branchrunner is skipped
            assertStateReached("parallelTwo", engine)
            //Still on parallelTwo since third entry triggered before any branchrunner tick could happen
            assertStateReached("parallelTwo", engine)
            assertEndReached(engine)
        }

        @Test
        fun testExitBreakpoints() {
            setup()

            val engine = createEngineAndRunEngineCycle(getStateMachineDefinition("exit"), manager, false)
            //stuck on first exit, entry executed, transition has not applied yet
            assertStateReached("first", engine)
            //stuck on parallelOne exit
            assertStateReached("parallelOne", engine)
            //ParallelThree exit breakpoint triggered in Join, but last entry still on parallelTwo since branchrunner is skipped
            assertStateReached("parallelTwo", engine)
            //Second exit breakpoint triggered in Join, but last entry still on parallelTwo since branchrunner is skipped
            assertStateReached("parallelTwo", engine)
            assertEndReached(engine)
        }

        @Test
        fun testStartBreakpoints() {
            setup()

            val engine = createEngineAndRunEngineCycle(getStateMachineDefinition("start"), manager, false)

            //stuck on split first -> parallelOne,parallelTwo start, no last fired split
            assertNull(getLastFiredSplitDetails(engine.traceLogger!!))
            assertStateReached("first", engine)
            //stuck on transition parallelOne -> parallelThree start, no last fired transition
            assertNull(getLastFiredTransitionDetails(engine.traceLogger))
            assertStateReached("parallelOne", engine)
            //stuck on transition parallelTwo -> parallelFour start, last fired transition is parallelOne -> parallelThree
            val details = assertNotNull(getLastFiredTransitionDetails(engine.traceLogger))
            assertEquals("parallelOne", details["from"])
            assertEquals("parallelThree", details["to"])
            assertStateReached("parallelTwo", engine)
            //stuck on join parallelThree,parallelFour -> third start, but last entry still on parallelTwo since branchrunner is skipped, no last fired join
            assertNull(getLastFiredJoinDetails(engine.traceLogger))
            assertStateReached("parallelTwo", engine)
            assertEndReached(engine)
        }

        @Test
        fun testEndBreakpoints() {
            setup()

            val engine = createEngineAndRunEngineCycle(getStateMachineDefinition("end"), manager, false)
            //stuck on split first -> parallelOne,parallelTwo end
            var details = assertNotNull(getLastFiredSplitDetails(engine.traceLogger!!))
            assertEquals("first", details["from"])
            assertEquals("parallelOne,parallelTwo", details["to"])
            assertStateReached("first", engine)
            //stuck on transition parallelOne -> parallelThree end
            details = assertNotNull(getLastFiredTransitionDetails(engine.traceLogger))
            assertEquals("parallelOne", details["from"])
            assertEquals("parallelThree", details["to"])
            assertStateReached("parallelOne", engine)
            //stuck on transition parallelTwo -> parallelFour end
            details = assertNotNull(getLastFiredTransitionDetails(engine.traceLogger))
            assertEquals("parallelTwo", details["from"])
            assertEquals("parallelFour", details["to"])
            assertStateReached("parallelTwo", engine)
            //stuck on join parallelThree,parallelFour -> third end, but last entry still on parallelTwo since branchrunner is skipped
            details = assertNotNull(getLastFiredJoinDetails(engine.traceLogger))
            assertEquals("parallelThree,parallelFour", details["from"])
            assertEquals("third", details["to"])
            assertStateReached("parallelTwo", engine)
            assertEndReached(engine)
        }

        @Test
        fun testBreakpointSimplified() {
            setup()

            //Pause verification
            val statemachineDefinition = "STATEMACHINE ATTACHED TO \"$globalModelElementId\" {\n" +
                    "    STATES {\n" +
                    "        INITIAL STATE \"first\" {\n" +
                    "            BREAKPOINT {\n" +
                    "                EXIT\n" +
                    "            }\n" +
                    "        }\n" +
                    "        STATE \"second\" {\n" +
                    "            BREAKPOINT {\n" +
                    "                ENTRY\n" +
                    "            }\n" +
                    "        }\n" +
                    "    }\n" +
                    "    TRANSITIONS {\n" +
                    "        TRANSITION \"first\" -> \"second\" {\n" +
                    "            BREAKPOINT {\n" +
                    "                START END\n" +
                    "            }\n" +
                    "        }\n" +
                    "    }\n" +
                    "}".trimIndent()

            var engine = createEngineAndRunEngineCycle(statemachineDefinition, manager, false)

            repeat(4) {
                assertStateReached("first", engine)
            }

            assertStateReached("second", engine)

            stopEngine(engine)

            //Breakpoint verification
            engine = createEngineAndRunEngineCycle(statemachineDefinition, manager, false)

            //stuck on exit
            assertTrue(engine.traceLogger!!.tracesOfType(TraceEventType.TRANSITION_START).isEmpty())
            assertTrue(engine.traceLogger.tracesOfType(TraceEventType.TRANSITION_FIRED).isEmpty())
            assertStateReached("first", engine)

            //stuck on start
            val transitionStart = assertNotNull(engine.traceLogger.tracesOfType(TraceEventType.TRANSITION_START).lastOrNull())
            assertEquals("first", transitionStart.details["from"])
            assertEquals("second", transitionStart.details["to"])
            assertTrue(engine.traceLogger.tracesOfType(TraceEventType.TRANSITION_FIRED).isEmpty())
            assertStateReached("first", engine)

            //stuck on end
            val transitionFired = assertNotNull(engine.traceLogger.tracesOfType(TraceEventType.TRANSITION_FIRED).lastOrNull())
            assertEquals("first", transitionFired.details["from"])
            assertEquals("second", transitionFired.details["to"])
            val lastTick = getCurrentTickNumber(engine.traceLogger)!!
            assertStateReached("first", engine)

            //stuck on entry
            assertEquals(lastTick + 1, getCurrentTickNumber(engine.traceLogger))
            assertStateReached("first", engine)
            stopEngine(engine)
        }
    }
}
