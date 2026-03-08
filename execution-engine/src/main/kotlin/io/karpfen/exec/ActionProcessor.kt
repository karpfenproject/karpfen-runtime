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
package io.karpfen.io.karpfen.exec

import instance.DataObject
import states.actions.*

/**
 * Executes [ActionBlock]s for a state machine context.
 *
 * An [ActionBlock] is a sequence of [ActionRule]s, each of which can:
 * - **SET(path, value)** – resolve a right-hand side value and write it into the model via
 *   [ModelQueryProcessor.updateProperty] / [ModelQueryProcessor.updateRelation].
 * - **APPEND(path, value)** – append a value to a list property via
 *   [ModelQueryProcessor.appendToList].
 * - **EVENT(domain, name)** – raise an internal event on the [EventProcessor].
 *
 * Right-hand side types:
 * - [ActionValueType.VALUE]  – literal string, parsed to the target property type.
 * - [ActionValueType.EVAL]   – inline macro (EVAL block) executed via [MacroProcessor].
 * - [ActionValueType.MACRO]  – named macro call, executed via [MacroProcessor.executeFullMacro].
 *
 * The context object is the [DataObject] identified by [contextObjectId] – usually the model
 * element the state machine is attached to.
 *
 * @property macroProcessor        Executes inline and full macros.
 * @property modelQueryProcessor   Reads and writes model data; notifies change observers.
 * @property eventProcessor        Raises internal events on the shared event bus.
 * @property contextObjectId       The id of the DataObject that is the current execution context.
 */
class ActionProcessor(
    val macroProcessor: MacroProcessor,
    val modelQueryProcessor: ModelQueryProcessor,
    val eventProcessor: EventProcessor,
    val contextObjectId: String
) {

    /**
     * Executes every [ActionRule] in [block] in order.
     *
     * @throws IllegalArgumentException if an unknown operation type or value type is encountered.
     * @throws RuntimeException if a Python macro execution fails.
     */
    fun executeBlock(block: ActionBlock) {
        modelQueryProcessor.beginBatch()
        for (rule in block.actions) {
            executeRule(rule)
        }
        modelQueryProcessor.commitBatch()
    }

    /**
     * Executes a single [ActionRule].
     */
    fun executeRule(rule: ActionRule) {
        when (rule.operationType) {
            ActionOperationType.SET    -> executeSet(rule)
            ActionOperationType.APPEND -> executeAppend(rule)
            ActionOperationType.EVENT  -> executeEvent(rule)
        }
    }

    // ---- SET ---------------------------------------------------------------

    /**
     * Resolves the right-hand side, then writes the result to the model property/relation
     * identified by [ActionRule.leftSide] (a path relative to the context object).
     *
     * For simple properties the value is written via [ModelQueryProcessor.updateProperty].
     * For object properties (relations) the result DataObject is written via
     * [ModelQueryProcessor.updateRelation].
     */
    private fun executeSet(rule: ActionRule) {
        val contextObj = modelQueryProcessor.getDataObjectById(contextObjectId)
        val path = rule.leftSide

        // Determine the expected return type by inspecting the metamodel for this path
        val expectedType = try {
            modelQueryProcessor.inferReturnType(contextObj, path)
        } catch (_: Exception) {
            // If the path can't be inferred (e.g., unknown), fall back to "string"
            "string"
        }

        val resolvedValue = resolveRightSide(rule.rightSide, expectedType, contextObj)
            ?: return // null result → skip (no-op)

        applyValueToPath(contextObj, path, resolvedValue)
    }

    // ---- APPEND ------------------------------------------------------------

    /**
     * Resolves the right-hand side and appends it to the list property at [ActionRule.leftSide].
     */
    private fun executeAppend(rule: ActionRule) {
        val contextObj = modelQueryProcessor.getDataObjectById(contextObjectId)
        val path = rule.leftSide

        // Determine the element type of the list
        val listType = try {
            modelQueryProcessor.inferReturnType(contextObj, path)
        } catch (_: Exception) {
            """list("string")"""
        }
        // Strip "list(" wrapper to get element type
        val elementType = extractElementType(listType)

        val resolvedValue = resolveRightSide(rule.rightSide, elementType, contextObj)
            ?: return

        // Navigate to the object that owns the list property
        val segments = path.split("->").map { it.trim() }
        if (segments.size == 1) {
            modelQueryProcessor.appendToList(contextObjectId, path, resolvedValue)
        } else {
            // Navigate to parent object, append on the last segment
            val parentPath = segments.dropLast(1).joinToString("->")
            val parentObj = modelQueryProcessor.resolvePathFromObject(contextObj, parentPath)
            if (parentObj is DataObject) {
                modelQueryProcessor.appendToList(parentObj.id, segments.last(), resolvedValue)
            } else {
                throw IllegalArgumentException(
                    "Cannot navigate to parent for APPEND on path '$path'"
                )
            }
        }
    }

    // ---- EVENT -------------------------------------------------------------

    /**
     * Raises an internal event. [ActionRule.leftSide] is the domain; the right-hand side
     * provides the event name (expected as [ActionValueType.VALUE] or a literal string from EVAL).
     */
    private fun executeEvent(rule: ActionRule) {
        val domain = rule.leftSide
        val eventName = when (rule.rightSide.actionValueType) {
            ActionValueType.VALUE -> (rule.rightSide as ValueActionRightSide).value
            ActionValueType.EVAL  -> {
                val contextObj = modelQueryProcessor.getDataObjectById(contextObjectId)
                val result = resolveRightSide(rule.rightSide, "string", contextObj)
                result?.toString() ?: return
            }
            ActionValueType.MACRO -> {
                val contextObj = modelQueryProcessor.getDataObjectById(contextObjectId)
                val result = resolveRightSide(rule.rightSide, "string", contextObj)
                result?.toString() ?: return
            }
        }
        eventProcessor.raiseInternalEvent(domain, eventName)
    }

    // ---- Right-side resolution ---------------------------------------------

    /**
     * Resolves an [ActionRightSide] to a concrete Kotlin value.
     *
     * @param rightSide     The right-side expression to evaluate.
     * @param expectedType  The expected result type string (e.g., "number", "Vector",
     *                      `reference("Obstacle")`, `list("string")`).
     * @param contextObj    The context [DataObject] for path resolution.
     * @return The resolved value or null if no result.
     */
    fun resolveRightSide(
        rightSide: ActionRightSide,
        expectedType: String,
        contextObj: DataObject
    ): Any? {
        return when (rightSide.actionValueType) {
            ActionValueType.VALUE -> {
                val literal = (rightSide as ValueActionRightSide).value
                parseLiteralToType(literal, expectedType)
            }
            ActionValueType.EVAL -> {
                val code = (rightSide as EvalActionRightSide).code
                macroProcessor.executeInlineMacro(code, expectedType)
            }
            ActionValueType.MACRO -> {
                val macroRS = rightSide as MacroActionRightSide
                macroProcessor.executeFullMacro(macroRS.macroName, macroRS.args)
            }
        }
    }

    // ---- Helpers -----------------------------------------------------------

    /**
     * Writes [value] into the model at the location specified by [path] relative to [contextObj].
     *
     * - If the path's last segment is a simple property → [ModelQueryProcessor.updateProperty].
     * - If it is an object relation      → [ModelQueryProcessor.updateRelation].
     */
    private fun applyValueToPath(contextObj: DataObject, path: String, value: Any) {
        val segments = path.split("->").map { it.trim() }

        // Navigate to the owner object
        val ownerObj: DataObject
        val propertyKey: String

        if (segments.size == 1) {
            ownerObj = contextObj
            propertyKey = segments[0]
        } else {
            val parentPath = segments.dropLast(1).joinToString("->")
            val parent = modelQueryProcessor.resolvePathFromObject(contextObj, parentPath)
            ownerObj = parent as? DataObject
                ?: throw IllegalArgumentException("Cannot navigate to parent for SET on path '$path'")
            propertyKey = segments.last()
        }

        // Determine whether the key refers to a simple property or a relation
        val isSimpleProp = ownerObj.properties.any { it.key == propertyKey }
        if (isSimpleProp) {
            modelQueryProcessor.updateProperty(ownerObj.id, propertyKey, value)
        } else {
            // When a macro returns an anonymous DataObject (empty id, e.g. a Vector built from
            // a Python dict like {"x":...,"y":...}), merge its properties into the *existing*
            // named target object rather than replacing the relation.  This ensures that
            // change notifications fire for the named child object (e.g. "turtleDirection")
            // so that WebSocket subscribers receive updates.
            if (value is DataObject && value.id.isEmpty()) {
                val existingTargets = ownerObj.getRel(propertyKey)
                if (existingTargets.isNotEmpty()) {
                    val target = existingTargets.first()
                    val propsMap = value.ofType.simpleProperties
                        .filter { !it.isList }
                        .mapNotNull { propDef ->
                            val vals = value.getProp(propDef.key)
                            if (vals.isNotEmpty()) propDef.key to vals.first() else null
                        }.toMap()
                    if (propsMap.isNotEmpty()) {
                        modelQueryProcessor.updateProperties(target.id, propsMap)
                        return
                    }
                }
            }
            modelQueryProcessor.updateRelation(ownerObj.id, propertyKey, value)
        }
    }

    /**
     * Parses a literal string value into the target type.
     *
     * - "number" → Double
     * - "boolean" → Boolean
     * - "string"  → String (unchanged)
     * - Complex / reference / list types → the string as-is (caller must handle)
     */
    private fun parseLiteralToType(literal: String, expectedType: String): Any {
        return when (expectedType.lowercase()) {
            "number"  -> literal.toDoubleOrNull()
                ?: throw IllegalArgumentException("Cannot parse '$literal' as number")
            "boolean" -> when (literal.lowercase()) {
                "true"  -> true
                "false" -> false
                else    -> throw IllegalArgumentException("Cannot parse '$literal' as boolean")
            }
            else -> literal
        }
    }

    /**
     * Extracts the element type from a list type string, e.g. `list("string")` → `"string"`.
     * Falls back to "string" if parsing fails.
     */
    private fun extractElementType(listType: String): String {
        val match = Regex("""list\("(\w+)"\)""").find(listType)
        return match?.groupValues?.get(1) ?: "string"
    }
}



