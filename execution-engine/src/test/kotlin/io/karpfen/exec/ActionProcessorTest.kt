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

import instance.DataObject
import io.karpfen.io.karpfen.exec.*
import io.karpfen.io.karpfen.messages.EventBus
import states.Macro
import states.actions.*
import states.macros.CodeBlock
import states.macros.Definition
import states.macros.ReturnsDirective
import states.macros.TakesDirective
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [ActionProcessor] covering:
 * - SET with VALUE / EVAL / MACRO right-hand sides
 * - APPEND with VALUE / EVAL right-hand sides
 * - EVENT with VALUE right-hand side
 * - nested path resolution (multi-segment paths)
 * - observer notification after model updates
 * - real Python execution for EVAL and MACRO rules
 */
class ActionProcessorTest {

    private lateinit var mqp: ModelQueryProcessor
    private lateinit var macros: List<Macro>
    private lateinit var eventBus: EventBus
    private lateinit var eventProcessor: EventProcessor
    private lateinit var macroProcessor: MacroProcessor

    @BeforeEach
    fun setUp() {
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        mqp = ModelQueryProcessor(metamodel, model)

        val addVectorsMacro = Macro(
            name = "add_vectors",
            takes = listOf(
                TakesDirective("v1", "Vector"),
                TakesDirective("v2", "Vector")
            ),
            returns = ReturnsDirective("Vector"),
            definition = Definition(CodeBlock(
                "return {\"x\": \$(v1->x) + \$(v2->x), \"y\": \$(v1->y) + \$(v2->y)}"
            ))
        )

        val scaleMacro = Macro(
            name = "scale_number",
            takes = listOf(TakesDirective("value", "number")),
            returns = ReturnsDirective("number"),
            definition = Definition(CodeBlock("return \$(value) * 2"))
        )

        val makeLogEntry = Macro(
            name = "make_log_entry",
            takes = listOf(TakesDirective("pos", "Vector")),
            returns = ReturnsDirective("string"),
            definition = Definition(CodeBlock(
                "return \"Robot at x=\" + str(\$(pos->x)) + \" y=\" + str(\$(pos->y))"
            ))
        )

        macros = listOf(addVectorsMacro, scaleMacro, makeLogEntry)

        eventBus = EventBus()
        eventProcessor = EventProcessor("test-engine", eventBus)
        macroProcessor = MacroProcessor(metamodel, model, macros, "turtle", mqp)
    }

    private fun makeActionProcessor(): ActionProcessor =
        ActionProcessor(macroProcessor, mqp, eventProcessor, "turtle")

    // ---- SET with VALUE -----------------------------------------------

    @Test
    fun `SET number property with literal value`() {
        val ap = makeActionProcessor()
        val rule = ActionRule(
            ActionOperationType.SET,
            "d_closest_obstacle",
            ValueActionRightSide("42.0")
        )
        ap.executeRule(rule)
        val turtle = mqp.getDataObjectById("turtle")
        assertEquals(42.0, turtle.getProp("d_closest_obstacle").first())
    }

    @Test
    fun `SET boolean property with literal value`() {
        // There is no boolean property in the test model, so we use a number and treat it
        // as a number to keep it simple. We re-use d_closest_wall instead.
        val ap = makeActionProcessor()
        val rule = ActionRule(
            ActionOperationType.SET,
            "d_closest_wall",
            ValueActionRightSide("7.5")
        )
        ap.executeRule(rule)
        val turtle = mqp.getDataObjectById("turtle")
        assertEquals(7.5, turtle.getProp("d_closest_wall").first())
    }

    @Test
    fun `SET nested number property via multi-segment path`() {
        val ap = makeActionProcessor()
        val rule = ActionRule(
            ActionOperationType.SET,
            "boundingBox->position->x",
            ValueActionRightSide("99.0")
        )
        ap.executeRule(rule)
        val pos = mqp.getDataObjectById("turtlePosition")
        assertEquals(99.0, pos.getProp("x").first())
    }

    @Test
    fun `SET nested number property via two-segment path`() {
        val ap = makeActionProcessor()
        val rule = ActionRule(
            ActionOperationType.SET,
            "boundingBox->diameter",
            ValueActionRightSide("1.5")
        )
        ap.executeRule(rule)
        val bb = mqp.getDataObjectById("turtleBoundingBox")
        assertEquals(1.5, bb.getProp("diameter").first())
    }

    // ---- SET with EVAL -----------------------------------------------

    @Test
    fun `SET with EVAL computes new x from position and direction`() {
        val ap = makeActionProcessor()
        // turtle x=5.0, direction x=0.0 → new x = 5.0 + 0.3*0.0 = 5.0
        val rule = ActionRule(
            ActionOperationType.SET,
            "boundingBox->position->x",
            EvalActionRightSide("return \$(boundingBox->position->x) + 0.3 * \$(direction->x)")
        )
        ap.executeRule(rule)
        val pos = mqp.getDataObjectById("turtlePosition")
        assertEquals(5.0, pos.getProp("x").first())
    }

    @Test
    fun `SET with EVAL computes new y from position and direction`() {
        val ap = makeActionProcessor()
        // turtle y=5.0, direction y=1.0 → new y = 5.0 + 0.3*1.0 = 5.3
        val rule = ActionRule(
            ActionOperationType.SET,
            "boundingBox->position->y",
            EvalActionRightSide("return \$(boundingBox->position->y) + 0.3 * \$(direction->y)")
        )
        ap.executeRule(rule)
        val pos = mqp.getDataObjectById("turtlePosition")
        assertEquals(5.3, pos.getProp("y").first() as Double, 0.001)
    }

    @Test
    fun `SET direction with EVAL returning dict updates relation`() {
        val ap = makeActionProcessor()
        // Returns a new Vector dict, which should update the direction relation
        val rule = ActionRule(
            ActionOperationType.SET,
            "direction",
            EvalActionRightSide("return {\"x\": 1.0, \"y\": 0.0}")
        )
        ap.executeRule(rule)
        val turtle = mqp.getDataObjectById("turtle")
        val dir = turtle.getRel("direction")
        assertEquals(1, dir.size)
        assertEquals(1.0, dir[0].getProp("x").first())
        assertEquals(0.0, dir[0].getProp("y").first())
    }

    // ---- SET with MACRO -----------------------------------------------

    @Test
    fun `SET direction with MACRO add_vectors`() {
        val ap = makeActionProcessor()
        // add_vectors(position(5,5), direction(0,1)) = (5,6)
        val rule = ActionRule(
            ActionOperationType.SET,
            "direction",
            MacroActionRightSide("add_vectors", listOf("boundingBox->position", "direction"))
        )
        ap.executeRule(rule)
        val turtle = mqp.getDataObjectById("turtle")
        val dir = turtle.getRel("direction")
        assertEquals(1, dir.size)
        assertEquals(5.0, dir[0].getProp("x").first())
        assertEquals(6.0, dir[0].getProp("y").first())
    }

    @Test
    fun `SET number property with MACRO scale_number`() {
        val ap = makeActionProcessor()
        // scale_number(100.0) = 200.0
        val rule = ActionRule(
            ActionOperationType.SET,
            "d_closest_obstacle",
            MacroActionRightSide("scale_number", listOf("d_closest_obstacle"))
        )
        ap.executeRule(rule)
        val turtle = mqp.getDataObjectById("turtle")
        assertEquals(200.0, turtle.getProp("d_closest_obstacle").first())
    }

    // ---- APPEND with VALUE -------------------------------------------

    @Test
    fun `APPEND string to log list with VALUE`() {
        val ap = makeActionProcessor()
        val rule = ActionRule(
            ActionOperationType.APPEND,
            "log",
            ValueActionRightSide("hello world")
        )
        ap.executeRule(rule)
        val turtle = mqp.getDataObjectById("turtle")
        val log = turtle.getProp("log")
        assertTrue(log.contains("hello world"))
    }

    @Test
    fun `APPEND multiple entries to log list`() {
        val ap = makeActionProcessor()
        ap.executeRule(ActionRule(ActionOperationType.APPEND, "log", ValueActionRightSide("entry1")))
        ap.executeRule(ActionRule(ActionOperationType.APPEND, "log", ValueActionRightSide("entry2")))
        ap.executeRule(ActionRule(ActionOperationType.APPEND, "log", ValueActionRightSide("entry3")))

        val turtle = mqp.getDataObjectById("turtle")
        val log = turtle.getProp("log")
        assertTrue(log.contains("entry1"))
        assertTrue(log.contains("entry2"))
        assertTrue(log.contains("entry3"))
    }

    // ---- APPEND with EVAL -------------------------------------------

    @Test
    fun `APPEND with EVAL result string`() {
        val ap = makeActionProcessor()
        // Inline macro returns a string that gets appended to the log
        val rule = ActionRule(
            ActionOperationType.APPEND,
            "log",
            EvalActionRightSide("return 'position: ' + str(\$(boundingBox->position->x))")
        )
        ap.executeRule(rule)
        val turtle = mqp.getDataObjectById("turtle")
        val log = turtle.getProp("log")
        assertTrue(log.any { it.toString().contains("position:") && it.toString().contains("5") })
    }

    // ---- APPEND with MACRO ------------------------------------------

    @Test
    fun `APPEND with MACRO returns string and appends`() {
        val ap = makeActionProcessor()
        // make_log_entry returns "Robot at x=5.0 y=5.0"
        val rule = ActionRule(
            ActionOperationType.APPEND,
            "log",
            MacroActionRightSide("make_log_entry", listOf("boundingBox->position"))
        )
        ap.executeRule(rule)
        val turtle = mqp.getDataObjectById("turtle")
        val log = turtle.getProp("log")
        assertTrue(log.any { it.toString().contains("Robot at") })
    }

    // ---- EVENT -------------------------------------------------------

    @Test
    fun `EVENT raises internal event on bus`() {
        val ap = makeActionProcessor()
        val rule = ActionRule(
            ActionOperationType.EVENT,
            "public",
            ValueActionRightSide("obstacle_detected")
        )
        ap.executeRule(rule)
        assertTrue(eventProcessor.hasEvent("public", "obstacle_detected"))
    }

    @Test
    fun `EVENT with different domains are independent`() {
        val ap = makeActionProcessor()
        ap.executeRule(ActionRule(ActionOperationType.EVENT, "local", ValueActionRightSide("ev1")))
        ap.executeRule(ActionRule(ActionOperationType.EVENT, "global", ValueActionRightSide("ev2")))

        assertTrue(eventProcessor.hasEvent("local", "ev1"))
        assertTrue(eventProcessor.hasEvent("global", "ev2"))
        assertFalse(eventProcessor.hasEvent("local", "ev2"))
        assertFalse(eventProcessor.hasEvent("global", "ev1"))
    }

    // ---- Full ActionBlock execution --------------------------------

    @Test
    fun `executeBlock runs all rules in order`() {
        val ap = makeActionProcessor()
        val block = ActionBlock(mutableListOf(
            ActionRule(ActionOperationType.SET, "d_closest_obstacle", ValueActionRightSide("10.0")),
            ActionRule(ActionOperationType.SET, "d_closest_wall", ValueActionRightSide("20.0")),
            ActionRule(ActionOperationType.APPEND, "log", ValueActionRightSide("updated")),
            ActionRule(ActionOperationType.EVENT, "local", ValueActionRightSide("done"))
        ))

        ap.executeBlock(block)

        val turtle = mqp.getDataObjectById("turtle")
        assertEquals(10.0, turtle.getProp("d_closest_obstacle").first())
        assertEquals(20.0, turtle.getProp("d_closest_wall").first())
        assertTrue(turtle.getProp("log").contains("updated"))
        assertTrue(eventProcessor.hasEvent("local", "done"))
    }

    // ---- Observer notification after SET ----------------------------

    @Test
    fun `SET triggers model change observer`() {
        var notifiedId: String? = null
        var notifiedJson: String? = null

        mqp.addChangePublisher { objectId, jsonValue ->
            notifiedId = objectId
            notifiedJson = jsonValue
        }

        val ap = makeActionProcessor()
        ap.executeRule(ActionRule(
            ActionOperationType.SET,
            "d_closest_obstacle",
            ValueActionRightSide("55.0")
        ))

        assertNotNull(notifiedId)
        assertEquals("turtle", notifiedId)
        assertNotNull(notifiedJson)
        assertTrue(notifiedJson!!.contains("55"))
    }

    @Test
    fun `APPEND triggers model change observer`() {
        var notified = false
        mqp.addChangePublisher { _, _ -> notified = true }

        val ap = makeActionProcessor()
        ap.executeRule(ActionRule(
            ActionOperationType.APPEND,
            "log",
            ValueActionRightSide("observer test")
        ))

        assertTrue(notified)
    }

    // ---- resolveRightSide unit tests --------------------------------

    @Test
    fun `resolveRightSide VALUE number`() {
        val ap = makeActionProcessor()
        val contextObj = mqp.getDataObjectById("turtle")
        val result = ap.resolveRightSide(ValueActionRightSide("3.14"), "number", contextObj)
        assertEquals(3.14, result)
    }

    @Test
    fun `resolveRightSide VALUE boolean true`() {
        val ap = makeActionProcessor()
        val contextObj = mqp.getDataObjectById("turtle")
        val result = ap.resolveRightSide(ValueActionRightSide("true"), "boolean", contextObj)
        assertEquals(true, result)
    }

    @Test
    fun `resolveRightSide VALUE string passthrough`() {
        val ap = makeActionProcessor()
        val contextObj = mqp.getDataObjectById("turtle")
        val result = ap.resolveRightSide(ValueActionRightSide("hello"), "string", contextObj)
        assertEquals("hello", result)
    }

    @Test
    fun `resolveRightSide EVAL returns number from python`() {
        val ap = makeActionProcessor()
        val contextObj = mqp.getDataObjectById("turtle")
        val result = ap.resolveRightSide(
            EvalActionRightSide("return \$(boundingBox->position->x) * 2"),
            "number",
            contextObj
        )
        assertEquals(10.0, (result as Number).toDouble(), 0.001)
    }

    @Test
    fun `resolveRightSide MACRO returns DataObject`() {
        val ap = makeActionProcessor()
        val contextObj = mqp.getDataObjectById("turtle")
        val result = ap.resolveRightSide(
            MacroActionRightSide("add_vectors", listOf("boundingBox->position", "direction")),
            "Vector",
            contextObj
        )
        assertTrue(result is DataObject)
        val v = result as DataObject
        assertEquals("Vector", v.ofType.name)
    }
}

