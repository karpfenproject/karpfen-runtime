/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.server

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import dsl.textual.KstatesDSLConverter
import io.karpfen.Engine
import io.karpfen.EngineTraceLogger
import io.karpfen.EngineTraceLogger.TraceEventType
import io.karpfen.env.EnvironmentHandler
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.messages.EventSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * System-level integration tests using the cleaning robot example from
 * `examples/single_robot_example/`.
 *
 * These tests load the real DSL files, parse them, run the engine for several
 * seconds, and then verify the execution traces to ensure only valid transitions
 * occurred and the state machine behaved correctly.
 *
 * Since the full cleaning robot example depends on Python macros (with numpy),
 * which may not be available in every CI environment, there are two groups:
 *
 * 1. **File parsing & structure tests** — verify the DSL files are syntactically
 *    correct and produce the expected model structure.
 * 2. **Engine execution tests** — use a simplified state machine (no numpy macros)
 *    that exercises all engine phases: ENTRY, DO, transitions with EVENT/EVAL
 *    conditions, unconditional transitions, and NOT LOOPING semantics.
 */
class SystemTest {

    // ---- Valid states and transitions as defined by the example ----

    /** All state names that may appear in the cleaning robot state machine. */
    private val validRobotStates = setOf(
        "ready", "observe", "react to obstacle", "react to wall",
        "drive", "drive fast", "drive slow"
    )

    /**
     * Valid transition pairs (from → to) as defined in the .kstates file.
     * Any fired transition whose from→to is NOT in this set is an error.
     */
    private val validTransitions = setOf(
        "ready" to "observe",
        "observe" to "react to obstacle",
        "observe" to "react to wall",
        "observe" to "drive",
        "drive" to "drive slow",
        "drive" to "drive fast",
        "drive" to "observe"
    )

    private lateinit var traceLogger: EngineTraceLogger

    @BeforeEach
    fun setup() {
        EnvironmentHandler.envs.clear()
        EnvironmentHandler.activeEnvs.clear()
        EnvironmentHandler.executionThreads.clear()
    }

    @AfterEach
    fun teardown() {
        EnvironmentHandler.executionThreads.forEach { (_, thread) ->
            try { thread.stop() } catch (_: Exception) {}
        }
        EnvironmentHandler.envs.clear()
        EnvironmentHandler.activeEnvs.clear()
        EnvironmentHandler.executionThreads.clear()
    }

    // ---- Helpers ----

    private fun loadExampleFile(filename: String): String {
        val paths = listOf(
            "examples/single_robot_example/$filename",
            "../examples/single_robot_example/$filename"
        )
        for (path in paths) {
            val file = File(path)
            if (file.exists()) return file.readText()
        }
        throw IllegalStateException(
            "Could not find example file '$filename'. " +
            "Searched: ${paths.joinToString()}"
        )
    }

    // ========== Group 1: DSL Parsing & Model Structure ==========

    @Test
    fun `example kmeta file parses successfully`() {
        val content = loadExampleFile("cleaning_robot.kmeta")
        val metamodel = KmetaDSLConverter.parseKmetaString(content)

        // Verify key types exist
        val typeNames = metamodel.types.map { it.name }
        assertTrue("Vector" in typeNames)
        assertTrue("Robot" in typeNames)
        assertTrue("Room" in typeNames)
        assertTrue("Obstacle" in typeNames)
        assertTrue("Wall" in typeNames)
        assertTrue("TwoDObject" in typeNames)
    }

    @Test
    fun `example kmodel file parses with metamodel`() {
        val kmeta = loadExampleFile("cleaning_robot.kmeta")
        val kmodel = loadExampleFile("cleaning_robot.kmodel")
        val metamodel = KmetaDSLConverter.parseKmetaString(kmeta)
        val model = KmodelDSLConverter.parseKmodelString(kmodel, metamodel)

        // model.objects contains only root-level objects;
        // embedded objects are found via collectAllObjects
        val allObjects = KmodelDSLConverter.collectAllObjects(model.objects)
        val objectIds = allObjects.map { it.id }
        assertTrue("turtle" in objectIds, "Robot 'turtle' must exist")
        assertTrue("chair" in objectIds, "Obstacle 'chair' must exist")
        assertTrue("table" in objectIds, "Obstacle 'table' must exist")
        assertTrue("turtlePosition" in objectIds)
        assertTrue("turtleDirection" in objectIds)

        // Verify turtle position
        val turtlePos = allObjects.first { it.id == "turtlePosition" }
        assertEquals(5.0, turtlePos.getProp("x").first())
        assertEquals(5.0, turtlePos.getProp("y").first())
    }

    @Test
    fun `example kstates file parses successfully`() {
        val content = loadExampleFile("cleaning_robot.kstates")
        val sm = KstatesDSLConverter.parseKstatesString(content)

        assertEquals("Robot", sm.attachedToClass)

        // Verify states
        val topLevelStateNames = sm.states.map { it.name }
        assertTrue("ready" in topLevelStateNames)
        assertTrue("observe" in topLevelStateNames)
        assertTrue("drive" in topLevelStateNames)

        // Verify ready is initial
        val readyState = sm.states.first { it.name == "ready" }
        assertTrue(readyState.isInitial)

        // Verify transitions
        assertEquals(7, sm.transitions.size, "Should have exactly 7 transitions")

        // Verify macros
        assertTrue(sm.macros.isNotEmpty(), "Should have macros defined")
        val macroNames = sm.macros.map { it.name }
        assertTrue("point_to_point_distance" in macroNames)
        assertTrue("circular_bounding_box_distance" in macroNames)
    }

    @Test
    fun `all transitions reference valid states`() {
        val content = loadExampleFile("cleaning_robot.kstates")
        val sm = KstatesDSLConverter.parseKstatesString(content)

        // Collect all state names (recursively)
        fun collectStateNames(states: List<states.State>): Set<String> {
            val names = mutableSetOf<String>()
            for (state in states) {
                names.add(state.name)
                names.addAll(collectStateNames(state.innerStates))
            }
            return names
        }
        val allStateNames = collectStateNames(sm.states)

        for (transition in sm.transitions) {
            assertTrue(
                transition.fromState in allStateNames,
                "Transition source '${transition.fromState}' is not a valid state. Valid: $allStateNames"
            )
            assertTrue(
                transition.toState in allStateNames,
                "Transition target '${transition.toState}' is not a valid state. Valid: $allStateNames"
            )
        }
    }

    @Test
    fun `hierarchical state structure is correct`() {
        val content = loadExampleFile("cleaning_robot.kstates")
        val sm = KstatesDSLConverter.parseKstatesString(content)

        // observe should contain "react to obstacle" and "react to wall"
        val observe = sm.states.first { it.name == "observe" }
        val observeChildren = observe.innerStates.map { it.name }
        assertTrue("react to obstacle" in observeChildren)
        assertTrue("react to wall" in observeChildren)

        // drive should contain "drive fast" and "drive slow"
        val drive = sm.states.first { it.name == "drive" }
        val driveChildren = drive.innerStates.map { it.name }
        assertTrue("drive fast" in driveChildren)
        assertTrue("drive slow" in driveChildren)
    }

    // ========== Group 2: Engine Execution with Simplified State Machine ==========

    /**
     * A simplified state machine that exercises all engine features without
     * requiring numpy or complex Python macros. It uses:
     * - EVENT conditions (ready → observe)
     * - EVAL conditions (observe → drive, drive sub-states)
     * - Unconditional transitions (drive → observe)
     * - NOT LOOPING semantics
     * - ENTRY and DO blocks with SET, APPEND, EVENT actions
     */
    private val simplifiedKstates = """
        STATEMACHINE ATTACHED TO "Robot" {
            STATES {
                INITIAL STATE "ready" {
                    ENTRY {
                        SET("d_closest_obstacle", "100.0")
                        SET("d_closest_wall", "100.0")
                    }
                }
                STATE "observe" {
                    ENTRY {
                        APPEND("log", "observing")
                    }
                    STATE "react to obstacle" {
                        DO {
                            APPEND("log", "reacting to obstacle")
                            EVENT("public", "obstacle_detected")
                        }
                    }
                    STATE "react to wall" {
                        DO {
                            APPEND("log", "reacting to wall")
                            EVENT("public", "wall_detected")
                        }
                    }
                }
                STATE "drive" {
                    ENTRY {
                        APPEND("log", "driving")
                    }
                    STATE "drive fast" {
                        DO {
                            APPEND("log", "driving fast")
                            SET("boundingBox->position->x", EVAL { return $(boundingBox->position->x) + 0.3 * $(direction->x) })
                            SET("boundingBox->position->y", EVAL { return $(boundingBox->position->y) + 0.3 * $(direction->y) })
                        }
                    }
                    STATE "drive slow" {
                        DO {
                            APPEND("log", "driving slow")
                            SET("boundingBox->position->x", EVAL { return $(boundingBox->position->x) + 0.1 * $(direction->x) })
                            SET("boundingBox->position->y", EVAL { return $(boundingBox->position->y) + 0.1 * $(direction->y) })
                        }
                    }
                }
            }
            TRANSITIONS {
                TRANSITION "ready" -> "observe" {
                    CONDITION {
                        EVENT("public", "start")
                    }
                }
                TRANSITION "observe" -> "react to obstacle" NOT LOOPING {
                    CONDITION {
                        EVAL { return $(d_closest_obstacle) < $(d_closest_wall) and $(d_closest_obstacle) < 0.25 }
                    }
                }
                TRANSITION "observe" -> "react to wall" NOT LOOPING {
                    CONDITION {
                        EVAL { return $(d_closest_wall) < $(d_closest_obstacle) and $(d_closest_wall) < 0.25 }
                    }
                }
                TRANSITION "observe" -> "drive" { }
                TRANSITION "drive" -> "drive slow" NOT LOOPING {
                    CONDITION {
                        EVAL { return $(d_closest_obstacle) < 1.0 or $(d_closest_wall) < 1.0 }
                    }
                }
                TRANSITION "drive" -> "drive fast" NOT LOOPING {
                    CONDITION {
                        EVAL { return $(d_closest_obstacle) >= 1.0 and $(d_closest_wall) >= 1.0 }
                    }
                }
                TRANSITION "drive" -> "observe" { }
            }
            MACROS { }
        }
    """.trimIndent()

    private fun buildEngineWithTrace(
        kstatesContent: String,
        tickDelay: Int = 50,
        logFile: String? = null
    ): Engine {
        val kmeta = loadExampleFile("cleaning_robot.kmeta")
        val kmodel = loadExampleFile("cleaning_robot.kmodel")
        val metamodel = KmetaDSLConverter.parseKmetaString(kmeta)
        val model = KmodelDSLConverter.parseKmodelString(kmodel, metamodel)
        val sm = KstatesDSLConverter.parseKstatesString(kstatesContent)

        traceLogger = EngineTraceLogger(
            engineId = "system-test",
            logFilePath = logFile,
            consoleOutput = false
        )

        return Engine(
            metamodel = metamodel,
            model = model,
            statemachineMap = mapOf("turtle" to sm),
            tickDelayMS = tickDelay,
            eventBus = EventBus(defaultTtlMs = 30_000),
            traceLogger = traceLogger
        )
    }

    @Test
    fun `engine starts in initial state ready`() {
        val engine = buildEngineWithTrace(simplifiedKstates)
        engine.start()
        Thread.sleep(300)
        engine.stop()

        val initialTraces = traceLogger.tracesOfType(TraceEventType.INITIAL_STATE)
        assertTrue(initialTraces.isNotEmpty(), "Should have an INITIAL_STATE trace")
        assertTrue(initialTraces[0].details["stack"]!!.contains("ready"))
    }

    @Test
    fun `engine executes onEntry of initial state`() {
        val engine = buildEngineWithTrace(simplifiedKstates)
        engine.start()
        Thread.sleep(300)
        engine.stop()

        val entryTraces = traceLogger.enteredStates()
        assertTrue("ready" in entryTraces, "Should have executed onEntry for 'ready'")
    }

    @Test
    fun `engine stays in ready without start event`() {
        val engine = buildEngineWithTrace(simplifiedKstates, tickDelay = 30)
        engine.start()
        Thread.sleep(500)
        engine.stop()

        // Should have no transitions fired — stuck in "ready" waiting for EVENT
        val transitions = traceLogger.firedTransitions()
        assertTrue(transitions.isEmpty(), "No transitions should fire without 'start' event. Got: $transitions")
    }

    @Test
    fun `engine transitions from ready to observe on start event`() {
        val engine = buildEngineWithTrace(simplifiedKstates, tickDelay = 30)
        engine.start()
        Thread.sleep(200)

        // Publish start event
        engine.eventProcessor.publishExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )
        Thread.sleep(500)
        engine.stop()

        val transitions = traceLogger.firedTransitions()
        assertTrue(transitions.any { it.contains("ready") && it.contains("observe") },
            "Should transition from ready to observe. Got: $transitions")
    }

    @Test
    fun `unconditional transition observe to drive fires automatically`() {
        val engine = buildEngineWithTrace(simplifiedKstates, tickDelay = 30)
        engine.start()
        Thread.sleep(100)

        engine.eventProcessor.publishExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )
        Thread.sleep(800)
        engine.stop()

        val transitions = traceLogger.firedTransitions()
        // After ready→observe, the unconditional observe→drive should fire
        assertTrue(transitions.any { it.contains("observe") && it.contains("drive") },
            "Unconditional transition observe→drive should fire. Got: $transitions")
    }

    @Test
    fun `all fired transitions are valid`() {
        val engine = buildEngineWithTrace(simplifiedKstates, tickDelay = 30)
        engine.start()
        Thread.sleep(100)

        engine.eventProcessor.publishExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )
        Thread.sleep(10_000)
        engine.stop()

        val firedTransitionTraces = traceLogger.tracesOfType(TraceEventType.TRANSITION_FIRED)
        for (trace in firedTransitionTraces) {
            val from = trace.details["from"]!!
            val to = trace.details["to"]!!
            assertTrue(
                (from to to) in validTransitions,
                "Invalid transition fired: $from -> $to. Valid: $validTransitions"
            )
        }
        assertTrue(firedTransitionTraces.isNotEmpty(), "At least some transitions should have fired")
    }

    @Test
    fun `state stack always contains valid states`() {
        val engine = buildEngineWithTrace(simplifiedKstates, tickDelay = 30)
        engine.start()
        Thread.sleep(100)

        engine.eventProcessor.publishExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )
        Thread.sleep(10_000)
        engine.stop()

        val tickTraces = traceLogger.tracesOfType(TraceEventType.TICK_START)
        for (trace in tickTraces) {
            val stack = trace.details["stack"]!!.split(",")
            for (stateName in stack) {
                assertTrue(
                    stateName in validRobotStates,
                    "Invalid state in stack: '$stateName'. Valid: $validRobotStates"
                )
            }
        }
    }

    @Test
    fun `drive sub-state is selected based on distance conditions`() {
        val engine = buildEngineWithTrace(simplifiedKstates, tickDelay = 30)
        engine.start()
        Thread.sleep(100)

        engine.eventProcessor.publishExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )
        // d_closest_obstacle = 100.0, d_closest_wall = 100.0 → both >= 1.0 → drive fast
        Thread.sleep(10_000)
        engine.stop()

        val transitions = traceLogger.firedTransitions()
        // Since both distances are >= 1.0, it should transition to "drive fast"
        assertTrue(
            transitions.any { it.contains("drive fast") },
            "With large distances, should drive fast. Transitions: $transitions"
        )
    }

    @Test
    fun `engine cycles between observe and drive`() {
        val engine = buildEngineWithTrace(simplifiedKstates, tickDelay = 30)
        engine.start()
        Thread.sleep(100)

        engine.eventProcessor.publishExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )
        // Each cycle (observe→drive→observe) requires multiple Python EVAL calls (~1-2s each).
        // Allow enough time for at least one full cycle.
        Thread.sleep(20_000)
        engine.stop()

        val transitions = traceLogger.firedTransitions()
        val observeToDrive = transitions.count { it.contains("observe") && it.contains("drive") && !it.contains("fast") && !it.contains("slow") }
        val driveToObserve = transitions.count { it.contains("drive") && it.contains("observe") }

        assertTrue(observeToDrive >= 1, "Should cycle observe→drive at least once. Got: $transitions")
        assertTrue(driveToObserve >= 1, "Should cycle drive→observe at least once. Got: $transitions")
    }

    @Test
    fun `no engine errors during execution`() {
        val engine = buildEngineWithTrace(simplifiedKstates, tickDelay = 30)
        engine.start()
        Thread.sleep(100)

        engine.eventProcessor.publishExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )
        Thread.sleep(10_000)
        engine.stop()

        val errors = traceLogger.errors()
        assertTrue(errors.isEmpty(), "Should have no engine errors. Got: ${errors.map { it.message }}")
    }

    /**
     * A minimal state machine designed specifically for testing model data changes.
     * Uses direct VALUE actions (no Python EVAL) to update position immediately
     * on entry, avoiding slow Python subprocess overhead.
     */
    private val positionUpdateKstates = """
        STATEMACHINE ATTACHED TO "Robot" {
            STATES {
                INITIAL STATE "idle" {
                    ENTRY {
                        SET("d_closest_obstacle", "100.0")
                        SET("d_closest_wall", "100.0")
                    }
                }
                STATE "moving" {
                    ENTRY {
                        APPEND("log", "started moving")
                        SET("boundingBox->position->y", "8.5")
                    }
                }
            }
            TRANSITIONS {
                TRANSITION "idle" -> "moving" {
                    CONDITION {
                        EVENT("public", "start")
                    }
                }
            }
            MACROS { }
        }
    """.trimIndent()

    @Test
    fun `model data changes during execution`() {
        val engine = buildEngineWithTrace(simplifiedKstates, tickDelay = 30, logFile = "build/test-traces/model-data-test.log")
        engine.start()
        Thread.sleep(200)

        engine.eventProcessor.publishExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )
        // Python subprocess calls take ~1-2s each. The engine needs several ticks
        // (ready→observe→drive→drive_fast) with EVAL transitions before DO runs.
        // Allow up to 25s for enough ticks to complete.
        Thread.sleep(25_000)
        engine.stop()

        // Verify the turtle position has been updated from (5.0, 5.0)
        val turtlePos = engine.modelQueryProcessor.getDataObjectById("turtlePosition")
        val x = turtlePos.getProp("x").first() as Double
        val y = turtlePos.getProp("y").first() as Double

        // Robot moves in direction (0,1) so y should increase, x stays same
        assertTrue(y > 5.0, "y position should have increased from 5.0, got $y")
        assertEquals(5.0, x, 0.001, "x position should stay at 5.0 (direction x=0)")
    }

    @Test
    fun `model data changes with fast VALUE actions`() {
        val engine = buildEngineWithTrace(positionUpdateKstates, tickDelay = 30)
        engine.start()
        Thread.sleep(200)

        engine.eventProcessor.publishExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )
        Thread.sleep(500)
        engine.stop()

        val turtlePos = engine.modelQueryProcessor.getDataObjectById("turtlePosition")
        val x = turtlePos.getProp("x").first() as Double
        val y = turtlePos.getProp("y").first() as Double

        assertEquals(8.5, y, 0.001, "y position should have been set to 8.5, got $y")
        assertEquals(5.0, x, 0.001, "x position should stay at 5.0")
    }

    @Test
    fun `log entries are appended during execution`() {
        val engine = buildEngineWithTrace(simplifiedKstates, tickDelay = 30)
        engine.start()
        Thread.sleep(100)

        engine.eventProcessor.publishExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )
        // Python EVAL transitions are slow; allow enough time to reach observe+drive states
        Thread.sleep(10_000)
        engine.stop()

        val turtle = engine.modelQueryProcessor.getDataObjectById("turtle")
        val log = turtle.getProp("log")
        assertTrue(log.isNotEmpty(), "Log should have entries after execution")
        assertTrue(log.any { it.toString().contains("observing") }, "Log should contain 'observing'")
        assertTrue(log.any { it.toString().contains("driving") }, "Log should contain 'driving'")
    }

    @Test
    fun `engine writes trace to log file`() {
        val logDir = "build/test-traces"
        val logFile = "$logDir/system-test-trace.log"

        val engine = buildEngineWithTrace(simplifiedKstates, tickDelay = 30, logFile = logFile)
        engine.start()
        Thread.sleep(100)

        engine.eventProcessor.publishExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )
        Thread.sleep(1000)
        engine.stop()

        val file = File(logFile)
        assertTrue(file.exists(), "Trace log file should have been created")
        val content = file.readText()
        assertTrue(content.contains("ENGINE_START"), "Log should contain ENGINE_START")
        assertTrue(content.contains("TICK_START"), "Log should contain TICK_START")
        assertTrue(content.contains("TRANSITION_FIRED"), "Log should contain TRANSITION_FIRED")

        // Clean up
        file.delete()
    }

    // ========== Group 3: Full System via HTTP API ==========

    @Test
    fun `full system test via API with simplified state machine`() {
        val kmeta = loadExampleFile("cleaning_robot.kmeta")
        val kmodel = loadExampleFile("cleaning_robot.kmodel")

        // Enable tracing
        EnvironmentHandler.traceConsoleOutput = false
        EnvironmentHandler.traceLogDirectory = "build/test-traces"

        val envKey = APIService.createEnvironment()
        APIService.receiveMetamodel(envKey, kmeta)
        APIService.receiveModel(envKey, kmodel)
        APIService.receiveStateMachine(envKey, "turtle", simplifiedKstates)
        APIService.updateTickDelay(envKey, 30)

        APIService.runEnvironment(envKey)
        APIService.startEnvironment(envKey)

        Thread.sleep(200)

        // Send start event via the EnvironmentThread's event queue
        val envThread = EnvironmentHandler.executionThreads[envKey]!!
        envThread.acceptExternalEvent(
            Event("public", "start", source = EventSource.EXTERNAL_MESSAGE)
        )

        Thread.sleep(2000)
        APIService.stopEnvironment(envKey)

        // Verify trace log was written
        val logFile = File("build/test-traces/engine-trace-$envKey.log")
        assertTrue(logFile.exists(), "Trace log should exist for env $envKey")
        val content = logFile.readText()
        assertTrue(content.contains("TRANSITION_FIRED"), "Should have transitions in the trace log")

        // Clean up
        logFile.delete()

        // Reset
        EnvironmentHandler.traceConsoleOutput = false
        EnvironmentHandler.traceLogDirectory = null
    }
}












