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

        // Returns an existing Obstacle by id (a `knows` reference target).
        val refTableMacro = Macro(
            name = "ref_table",
            takes = emptyList(),
            returns = ReturnsDirective("""reference("Obstacle")"""),
            definition = Definition(CodeBlock("return {\"__id__\": \"table\"}"))
        )

        // Builds a brand-new Obstacle (a `has` target), including its embedded boundingBox/position.
        val makeObstacleMacro = Macro(
            name = "make_obstacle",
            takes = emptyList(),
            returns = ReturnsDirective("Obstacle"),
            definition = Definition(CodeBlock(
                "return {\"boundingBox\": {\"diameter\": 0.5, \"position\": {\"x\": 1.0, \"y\": 1.0}}}"
            ))
        )

        // Returns a list of strings (for SETLIST).
        val makeLogMacro = Macro(
            name = "make_log",
            takes = emptyList(),
            returns = ReturnsDirective("""list("string")"""),
            definition = Definition(CodeBlock("return [\"a\", \"b\", \"c\"]"))
        )

        macros = listOf(addVectorsMacro, scaleMacro, makeLogEntry, refTableMacro, makeObstacleMacro, makeLogMacro)

        eventBus = EventBus()
        eventProcessor = EventProcessor("test-engine", eventBus)
        macroProcessor = MacroProcessor(metamodel, model, macros, "turtle", mqp)
    }

    private fun makeActionProcessor(): ActionProcessor =
        ActionProcessor(macroProcessor, mqp, eventProcessor, "turtle")

    /** An action processor whose context is the Room "APB 2101" (which owns `has` list relations). */
    private fun makeRoomActionProcessor(): ActionProcessor {
        val roomMacroProcessor = MacroProcessor(mqp.metamodel, mqp.model, macros, "APB 2101", mqp)
        return ActionProcessor(roomMacroProcessor, mqp, eventProcessor, "APB 2101")
    }

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
    fun `SETOBJ direction with EVAL returning new Vector replaces the has-relation`() {
        val ap = makeActionProcessor()
        // EVAL returns a new Vector dict; SETOBJ embeds it into the `has` relation "direction".
        val rule = ActionRule(
            ActionOperationType.SETOBJ,
            "self",
            EvalActionRightSide("return {\"x\": 1.0, \"y\": 0.0}"),
            secondSide = "direction"
        )
        ap.executeRule(rule)
        val turtle = mqp.getDataObjectById("turtle")
        val dir = turtle.getRel("direction")
        assertEquals(1, dir.size)
        assertEquals(1.0, dir[0].getProp("x").first())
        assertEquals(0.0, dir[0].getProp("y").first())
    }

    // ---- SETOBJ with MACRO --------------------------------------------

    @Test
    fun `SETOBJ direction with MACRO add_vectors`() {
        val ap = makeActionProcessor()
        // add_vectors(position(5,5), direction(0,1)) = (5,6)
        val rule = ActionRule(
            ActionOperationType.SETOBJ,
            "self",
            MacroActionRightSide("add_vectors", listOf("boundingBox->position", "direction")),
            secondSide = "direction"
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
        val block = ActionBlock(mutableListOf<ActionItem>(
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

        mqp.addChangePublisher(object : ModelChangePublisher {
            override fun onObjectChanged(objectId: String, jsonValue: String) {
                notifiedId = objectId
                notifiedJson = jsonValue
            }
        })

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
        mqp.addChangePublisher(object : ModelChangePublisher {
            override fun onObjectChanged(objectId: String, jsonValue: String) { notified = true }
        })

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
        val result = ap.resolveRightSide(ValueActionRightSide("3.14"), "number")
        assertEquals(3.14, result)
    }

    @Test
    fun `resolveRightSide VALUE boolean true`() {
        val ap = makeActionProcessor()
        val result = ap.resolveRightSide(ValueActionRightSide("true"), "boolean")
        assertEquals(true, result)
    }

    @Test
    fun `resolveRightSide VALUE string passthrough`() {
        val ap = makeActionProcessor()
        val result = ap.resolveRightSide(ValueActionRightSide("hello"), "string")
        assertEquals("hello", result)
    }

    @Test
    fun `resolveRightSide EVAL returns number from python`() {
        val ap = makeActionProcessor()
        val result = ap.resolveRightSide(
            EvalActionRightSide("return \$(boundingBox->position->x) * 2"),
            "number"
        )
        assertEquals(10.0, (result as Number).toDouble(), 0.001)
    }

    @Test
    fun `resolveRightSide MACRO returns DataObject`() {
        val ap = makeActionProcessor()
        val result = ap.resolveRightSide(
            MacroActionRightSide("add_vectors", listOf("boundingBox->position", "direction")),
            "Vector"
        )
        assertTrue(result is DataObject)
        val v = result as DataObject
        assertEquals("Vector", v.ofType.name)
    }

    // ---- SETLIST / DROPLIST ------------------------------------------

    @Test
    fun `SETLIST overwrites a simple list property`() {
        val ap = makeActionProcessor()
        ap.executeRule(ActionRule(ActionOperationType.APPEND, "log", ValueActionRightSide("old entry")))
        ap.executeRule(ActionRule(ActionOperationType.SETLIST, "log", MacroActionRightSide("make_log", emptyList())))
        val log = mqp.getDataObjectById("turtle").getProp("log")
        assertEquals(listOf("a", "b", "c"), log)
    }

    @Test
    fun `DROPLIST clears a simple list property`() {
        val ap = makeActionProcessor()
        ap.executeRule(ActionRule(ActionOperationType.APPEND, "log", ValueActionRightSide("entry")))
        ap.executeRule(ActionRule(ActionOperationType.DROPLIST, "log"))
        assertTrue(mqp.getDataObjectById("turtle").getProp("log").isEmpty())
    }

    // ---- SET strictness (scalars only) -------------------------------

    @Test
    fun `SET on a relation is rejected`() {
        val ap = makeActionProcessor()
        assertThrows(IllegalArgumentException::class.java) {
            ap.executeRule(ActionRule(ActionOperationType.SET, "direction", ValueActionRightSide("x")))
        }
    }

    @Test
    fun `SET on a list property is rejected`() {
        val ap = makeActionProcessor()
        assertThrows(IllegalArgumentException::class.java) {
            ap.executeRule(ActionRule(ActionOperationType.SET, "log", ValueActionRightSide("x")))
        }
    }

    @Test
    fun `APPEND on a scalar property is rejected`() {
        val ap = makeActionProcessor()
        assertThrows(IllegalArgumentException::class.java) {
            ap.executeRule(ActionRule(ActionOperationType.APPEND, "d_closest_obstacle", ValueActionRightSide("1.0")))
        }
    }

    // ---- SETOBJ (knows / has) ----------------------------------------

    @Test
    fun `SETOBJ repoints a knows relation to an existing object`() {
        val ap = makeActionProcessor()
        // closest_obstacle starts as "chair"; repoint it to the existing "table".
        ap.executeRule(ActionRule(
            ActionOperationType.SETOBJ, "self",
            MacroActionRightSide("ref_table", emptyList()),
            secondSide = "closest_obstacle"
        ))
        val rel = mqp.getDataObjectById("turtle").getRel("closest_obstacle")
        assertEquals(1, rel.size)
        assertEquals("table", rel.first().id)
    }

    @Test
    fun `SETOBJ into a has relation rejects an already-contained object`() {
        val ap = makeActionProcessor()
        // turtleDirection already exists (is has-contained), so it cannot be embedded again.
        assertThrows(IllegalArgumentException::class.java) {
            ap.executeRule(ActionRule(
                ActionOperationType.SETOBJ, "self",
                ValueActionRightSide("direction"),
                secondSide = "direction"
            ))
        }
    }

    // ---- APPENDOBJ (knows / has) -------------------------------------

    @Test
    fun `APPENDOBJ adds an existing object to a knows list`() {
        val ap = makeActionProcessor()
        val before = mqp.getDataObjectById("turtle").getRel("obstacles").size
        ap.executeRule(ActionRule(
            ActionOperationType.APPENDOBJ, "self",
            MacroActionRightSide("ref_table", emptyList()),
            secondSide = "obstacles"
        ))
        assertEquals(before + 1, mqp.getDataObjectById("turtle").getRel("obstacles").size)
    }

    @Test
    fun `APPENDOBJ embeds a new object into a has list and registers it`() {
        val ap = makeRoomActionProcessor()
        val before = mqp.getDataObjectById("APB 2101").getRel("obstacles").size
        ap.executeRule(ActionRule(
            ActionOperationType.APPENDOBJ, "self",
            MacroActionRightSide("make_obstacle", emptyList()),
            secondSide = "obstacles"
        ))
        val obstacles = mqp.getDataObjectById("APB 2101").getRel("obstacles")
        assertEquals(before + 1, obstacles.size)
        val added = obstacles.last()
        // The new object (and its embedded subtree) must be registered and reachable by id.
        assertEquals(added, mqp.getDataObjectById(added.id))
        assertEquals(0.5, added.getRel("boundingBox").first().getProp("diameter").first())
    }

    @Test
    fun `APPENDOBJ into a knows list rejects a brand-new object`() {
        val ap = makeActionProcessor()
        // make_obstacle builds a new object; a `knows` list must reference existing objects only.
        assertThrows(IllegalArgumentException::class.java) {
            ap.executeRule(ActionRule(
                ActionOperationType.APPENDOBJ, "self",
                MacroActionRightSide("make_obstacle", emptyList()),
                secondSide = "obstacles"
            ))
        }
    }

    // ---- DROPOBJ -----------------------------------------------------

    @Test
    fun `DROPOBJ removes the object, its subtree, and inbound knows references`() {
        val deletedIds = mutableListOf<String>()
        mqp.addChangePublisher(object : ModelChangePublisher {
            override fun onObjectChanged(objectId: String, jsonValue: String) {}
            override fun onObjectDeleted(objectId: String) { deletedIds.add(objectId) }
        })

        val ap = makeActionProcessor()
        // closest_obstacle -> chair. Dropping chair must remove it everywhere.
        ap.executeRule(ActionRule(ActionOperationType.DROPOBJ, "closest_obstacle"))

        // chair and its embedded subtree are gone from the model.
        assertThrows(IllegalArgumentException::class.java) { mqp.getDataObjectById("chair") }
        assertTrue(deletedIds.contains("chair"))
        assertTrue(deletedIds.contains("chairBoundingBox"))
        assertTrue(deletedIds.contains("chairPosition"))

        // Inbound knows references are scrubbed.
        val turtle = mqp.getDataObjectById("turtle")
        assertTrue(turtle.getRel("closest_obstacle").isEmpty())
        assertTrue(turtle.getRel("obstacles").none { it.id == "chair" })

        // The has container (Room) no longer contains chair.
        assertTrue(mqp.getDataObjectById("APB 2101").getRel("obstacles").none { it.id == "chair" })
    }

    // ---- WITH ... AS binding block -----------------------------------

    @Test
    fun `WITH binds a macro result once for value-wise updates preserving object id`() {
        val ap = makeActionProcessor()
        // add_vectors(position(5,5), direction(0,1)) = (5,6); scatter into the existing direction Vector.
        val block = ActionBlock(mutableListOf<ActionItem>(
            WithBlock(
                MacroActionRightSide("add_vectors", listOf("boundingBox->position", "direction")),
                "dir",
                ActionBlock(mutableListOf<ActionItem>(
                    ActionRule(ActionOperationType.SET, "direction->x", EvalActionRightSide("return \$(dir->x)")),
                    ActionRule(ActionOperationType.SET, "direction->y", EvalActionRightSide("return \$(dir->y)"))
                ))
            )
        ))
        ap.executeBlock(block)

        val dir = mqp.getDataObjectById("turtleDirection") // id preserved (not replaced)
        assertEquals(5.0, dir.getProp("x").first() as Double, 0.001)
        assertEquals(6.0, dir.getProp("y").first() as Double, 0.001)
    }
}

