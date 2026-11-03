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
package io.karpfen.exec

import io.karpfen.Engine
import io.karpfen.io.karpfen.messages.EventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers the tick gate of [Engine] over the cleaning-robot fixture: reads between ticks see whole action blocks, and
 * [Engine.isTicking] follows the engine thread.
 */
class EngineTickBoundaryTest {

    /** Every engine a test built, stopped after the test. */
    private val engines = mutableListOf<Engine>()

    /** Stops every engine the test built. */
    @AfterEach
    fun stopEngines() {
        engines.forEach { it.stop() }
    }

    @Test
    fun `reads between ticks never see a half-applied action block`() {
        val engine = startEngine("turtle", ALTERNATING_MACHINE)
        awaitCondition("the first tick changed the model") {
            engine.readBetweenTicks(WAIT_MS) { distanceToObstacle(engine) } != 100.0
        }
        val seen = mutableListOf<Pair<Double, Double>>()

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (seen.size < READS || seen.map { it.first }.distinct().size < 2) {
            if (System.nanoTime() > deadline) fail("Saw only ${seen.map { it.first }.distinct()} in ${seen.size} reads")
            val distances = engine.readBetweenTicks(WAIT_MS) { distanceToObstacle(engine) to distanceToWall(engine) }
            seen.add(assertNotNull(distances))
        }

        val halfApplied = seen.filter { (obstacle, wall) -> obstacle != wall }
        assertTrue(halfApplied.isEmpty(), "Half-applied blocks seen: $halfApplied")
    }

    @Test
    fun `the engine reports ticking from start until stop`() {
        val engine = buildEngine("turtle", ALTERNATING_MACHINE)
        assertFalse(engine.isTicking)

        engine.start()
        assertTrue(engine.isTicking)
        awaitCondition("the first tick changed the model") {
            engine.readBetweenTicks(WAIT_MS) { distanceToObstacle(engine) } != 100.0
        }
        assertTrue(engine.isTicking)

        engine.stop()
        assertFalse(engine.isTicking)
    }

    @Test
    fun `the engine reports not ticking after its thread ended on its own`() {
        val engine = startEngine("ghost", ALTERNATING_MACHINE)

        awaitCondition("the engine thread ended") { !engine.isTicking }

        assertEquals("at rest", engine.readBetweenTicks(WAIT_MS) { "at rest" })
        assertEquals(100.0, distanceToObstacle(engine))
    }

    /** Builds an engine with tick delay 0 over a fresh fixture model, the machine attached to [attachedTo]. */
    private fun buildEngine(attachedTo: String, machine: String): Engine {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        val stateMachine = TestFixtures.buildStateMachine(machine)
        return Engine(metamodel, model, mapOf(attachedTo to stateMachine), tickDelayMS = 0, eventBus = EventBus())
            .also { engines.add(it) }
    }

    /** Builds and starts an engine like [buildEngine]. */
    private fun startEngine(attachedTo: String, machine: String): Engine =
        buildEngine(attachedTo, machine).also { it.start() }

    /** The turtle's `d_closest_obstacle`, read on the calling thread. */
    private fun distanceToObstacle(engine: Engine): Double =
        engine.modelQueryProcessor.getDataObjectById("turtle").getProp("d_closest_obstacle").first() as Double

    /** The turtle's `d_closest_wall`, read on the calling thread. */
    private fun distanceToWall(engine: Engine): Double =
        engine.modelQueryProcessor.getDataObjectById("turtle").getProp("d_closest_wall").first() as Double

    /** Polls [condition] until it holds, failing with [description] after a generous deadline. */
    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting until $description")
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        /** Generous deadline for every wait of these tests. */
        const val WAIT_MS = 10_000L

        /** Pause between two polls of a condition. */
        const val POLL_MS = 5L

        /** How many reads the half-applied-block test makes at least. */
        const val READS = 500

        /**
         * A machine that switches state on every tick; each state's `ENTRY` block writes the same literal to both
         * distances, so a read in the middle of a block would see them differ.
         */
        val ALTERNATING_MACHINE = """
            STATEMACHINE ATTACHED TO "Robot" {
                STATES {
                    INITIAL STATE "near" {
                        ENTRY {
                            SET("d_closest_obstacle", "1.0")
                            SET("d_closest_wall", "1.0")
                        }
                    }
                    STATE "far" {
                        ENTRY {
                            SET("d_closest_obstacle", "2.0")
                            SET("d_closest_wall", "2.0")
                        }
                    }
                }
                TRANSITIONS {
                    TRANSITION "near" -> "far" { }
                    TRANSITION "far" -> "near" { }
                }
            }
        """.trimIndent()
    }
}
