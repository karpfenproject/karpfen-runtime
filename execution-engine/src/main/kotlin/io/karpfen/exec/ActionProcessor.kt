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
import meta.AssociationType
import meta.ClassTypeProperty
import meta.SimplePropertyType
import states.actions.*

/**
 * Executes [ActionBlock]s for a state machine context.
 *
 * An [ActionBlock] is a sequence of [ActionItem]s. A leaf [ActionRule] performs one of:
 *
 * Simple-property operations (primitive scalars / lists only):
 * - **SET(path, value)** – overwrite a single scalar via [ModelQueryProcessor.setScalar].
 * - **APPEND(path, value)** – append one element to a simple list via [ModelQueryProcessor.appendToList].
 * - **SETLIST(path, value)** – overwrite a whole simple list via [ModelQueryProcessor.setSimpleList].
 * - **DROPLIST(path)** – clear a simple list via [ModelQueryProcessor.clearSimpleList].
 *
 * Object/relation operations (`has`/`knows` relations):
 * - **SETOBJ(source, relation, target)** – replace an atomic relation target via
 *   [ModelQueryProcessor.setObjectRelation].
 * - **APPENDOBJ(source, relation, target)** – add an object to a list relation via
 *   [ModelQueryProcessor.appendObjectRelation].
 * - **DROPOBJ(path)** – remove an object and all relations pointing to it via
 *   [ModelQueryProcessor.dropObject].
 *
 * - **EVENT(domain, name)** – raise an internal event on the [EventProcessor].
 *
 * A [WithBlock] evaluates a macro once and binds its result to a name for its body; an [InScopeBlock]
 * runs its body only when the access paths it names are currently available.
 *
 * Right-hand side types: [ActionValueType.VALUE] (literal / data-access path), [ActionValueType.EVAL]
 * (inline macro), [ActionValueType.MACRO] (named macro call).
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
     * Executes every item in [block] in order.
     *
     * [eventContext] is the payload of the event in scope, if any. It seeds the resolution scope so that
     * `$(event->...)` is resolvable inside this block and an `IF IN SCOPE` block can check its availability.
     */
    fun executeBlock(block: ActionBlock, eventContext: DataObject? = null) {
        modelQueryProcessor.beginBatch()
        executeItems(block.items, ModelQueryProcessor.eventScope(eventContext))
        modelQueryProcessor.commitBatch()
    }

    /**
     * Executes a list of action items under the given resolution [scope], recursing into [InScopeBlock]s
     * whose paths are available and into [WithBlock]s with their binding added to the scope.
     */
    private fun executeItems(items: List<ActionItem>, scope: Map<String, Any?>) {
        for (item in items) {
            when (item) {
                is ActionRule -> dispatch(item, scope)
                is InScopeBlock -> {
                    if (isInScope(item.paths, scope)) {
                        executeItems(item.body.items, scope)
                    }
                }
                is WithBlock -> executeWithBlock(item, scope)
            }
        }
    }

    /**
     * Evaluates the WITH macro exactly once in the current [scope], then runs the body with the result
     * bound to [WithBlock.name] (readable as `$(name->...)` or `$(name)`).
     */
    private fun executeWithBlock(block: WithBlock, scope: Map<String, Any?>) {
        val result = macroProcessor.executeFullMacro(block.macro.macroName, block.macro.args, scope)
        executeItems(block.body.items, scope + (block.name to result))
    }

    /**
     * Returns true when every path in [paths] currently resolves to a value against the [scope] or the
     * context object. This is what lets an `IF IN SCOPE` block run only when the data it needs is present.
     */
    private fun isInScope(paths: List<String>, scope: Map<String, Any?>): Boolean {
        val contextObj = modelQueryProcessor.getDataObjectById(contextObjectId)
        return paths.all { path ->
            modelQueryProcessor.tryResolvePathInScope(contextObj, scope, path) != null
        }
    }

    /**
     * Executes a single [ActionRule]. [eventContext] is the event in scope, if any; it seeds the
     * resolution scope. (Used directly by tests; block execution goes through [executeItems].)
     */
    fun executeRule(rule: ActionRule, eventContext: DataObject? = null) {
        dispatch(rule, ModelQueryProcessor.eventScope(eventContext))
    }

    private fun dispatch(rule: ActionRule, scope: Map<String, Any?>) {
        when (rule.operationType) {
            ActionOperationType.SET       -> executeSet(rule, scope)
            ActionOperationType.APPEND    -> executeAppend(rule, scope)
            ActionOperationType.SETLIST   -> executeSetList(rule, scope)
            ActionOperationType.DROPLIST  -> executeDropList(rule)
            ActionOperationType.SETOBJ    -> executeSetObj(rule, scope)
            ActionOperationType.APPENDOBJ -> executeAppendObj(rule, scope)
            ActionOperationType.DROPOBJ   -> executeDropObj(rule)
            ActionOperationType.EVENT     -> executeEvent(rule, scope)
        }
    }

    // ---- Simple-property operations ----------------------------------------

    private fun executeSet(rule: ActionRule, scope: Map<String, Any?>) {
        val (owner, key) = resolveOwnerAndKey(rule.leftSide)
        val type = scalarTypeOrThrow(owner, key)
        val value = resolveRightSide(rule.rightSide!!, type, scope) ?: return
        modelQueryProcessor.setScalar(owner.id, key, value)
    }

    private fun executeAppend(rule: ActionRule, scope: Map<String, Any?>) {
        val (owner, key) = resolveOwnerAndKey(rule.leftSide)
        val elementType = simpleListElementTypeOrThrow(owner, key)
        val value = resolveRightSide(rule.rightSide!!, elementType, scope) ?: return
        modelQueryProcessor.appendToList(owner.id, key, value)
    }

    private fun executeSetList(rule: ActionRule, scope: Map<String, Any?>) {
        val (owner, key) = resolveOwnerAndKey(rule.leftSide)
        val elementType = simpleListElementTypeOrThrow(owner, key)
        val rs = rule.rightSide!!
        // A list literal has no surface syntax, so a VALUE is parsed as a single element; EVAL/MACRO
        // are expected to return a list of the element type.
        val resolved = if (rs.actionValueType == ActionValueType.VALUE) {
            resolveRightSide(rs, elementType, scope)
        } else {
            resolveRightSide(rs, """list("$elementType")""", scope)
        } ?: return
        val values = when (resolved) {
            is List<*> -> resolved.filterNotNull()
            else -> listOf(resolved)
        }
        modelQueryProcessor.setSimpleList(owner.id, key, values)
    }

    private fun executeDropList(rule: ActionRule) {
        val (owner, key) = resolveOwnerAndKey(rule.leftSide)
        simpleListElementTypeOrThrow(owner, key) // validates the target is a simple list property
        modelQueryProcessor.clearSimpleList(owner.id, key)
    }

    // ---- Object / relation operations --------------------------------------

    private fun executeSetObj(rule: ActionRule, scope: Map<String, Any?>) {
        val owner = resolveSource(rule.leftSide)
        val relation = rule.secondSide!!
        val relDef = objectRelationOrThrow(owner, relation)
        val target = resolveTargetObject(rule, relDef, scope)
        modelQueryProcessor.setObjectRelation(owner.id, relation, target)
    }

    private fun executeAppendObj(rule: ActionRule, scope: Map<String, Any?>) {
        val owner = resolveSource(rule.leftSide)
        val relation = rule.secondSide!!
        val relDef = objectRelationOrThrow(owner, relation)
        val target = resolveTargetObject(rule, relDef, scope)
        modelQueryProcessor.appendObjectRelation(owner.id, relation, target)
    }

    private fun executeDropObj(rule: ActionRule) {
        val contextObj = modelQueryProcessor.getDataObjectById(contextObjectId)
        val target = modelQueryProcessor.resolvePathFromObject(contextObj, rule.leftSide) as? DataObject
            ?: throw IllegalArgumentException("DROPOBJ path '${rule.leftSide}' does not resolve to an object")
        require(target.id != contextObjectId) {
            "DROPOBJ cannot remove the state machine's own context object '$contextObjectId'"
        }
        modelQueryProcessor.dropObject(target.id)
    }

    // ---- EVENT -------------------------------------------------------------

    private fun executeEvent(rule: ActionRule, scope: Map<String, Any?>) {
        val domain = rule.leftSide
        val rs = rule.rightSide!!
        val eventName = when (rs) {
            is ValueActionRightSide -> rs.value
            else -> resolveRightSide(rs, "string", scope)?.toString() ?: return
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
     * @param scope         The resolution scope (event + WITH bindings) for `$(...)` paths.
     * @return The resolved value or null if no result.
     */
    fun resolveRightSide(
        rightSide: ActionRightSide,
        expectedType: String,
        scope: Map<String, Any?> = emptyMap()
    ): Any? {
        return when (rightSide.actionValueType) {
            ActionValueType.VALUE -> parseLiteralToType((rightSide as ValueActionRightSide).value, expectedType)
            ActionValueType.EVAL -> macroProcessor.executeInlineMacro((rightSide as EvalActionRightSide).code, expectedType, scope)
            ActionValueType.MACRO -> {
                val macroRS = rightSide as MacroActionRightSide
                macroProcessor.executeFullMacro(macroRS.macroName, macroRS.args, scope)
            }
        }
    }

    /**
     * Resolves the target object of a SETOBJ/APPENDOBJ. A VALUE is a data-access path to an existing
     * object (relative to the context); an EVAL/MACRO produces an object whose expected type follows the
     * relation kind — `reference("T")` for a `knows` (LINK) relation (look up existing), or `"T"` for a
     * `has` (EMBEDDED) relation (build new).
     */
    private fun resolveTargetObject(rule: ActionRule, relDef: ClassTypeProperty, scope: Map<String, Any?>): DataObject {
        val rs = rule.rightSide!!
        return when (rs) {
            is ValueActionRightSide -> {
                val contextObj = modelQueryProcessor.getDataObjectById(contextObjectId)
                modelQueryProcessor.resolvePathFromObject(contextObj, rs.value) as? DataObject
                    ?: throw IllegalArgumentException("Target path '${rs.value}' does not resolve to an object")
            }
            else -> {
                val expected = if (relDef.associationType == AssociationType.LINK) {
                    """reference("${relDef.reference.classTypeName}")"""
                } else {
                    relDef.reference.classTypeName
                }
                resolveRightSide(rs, expected, scope) as? DataObject
                    ?: throw IllegalArgumentException(
                        "Target expression for relation '${relDef.key}' did not produce an object"
                    )
            }
        }
    }

    // ---- Path / metamodel helpers ------------------------------------------

    /**
     * Navigates [path] (relative to the context object) to its owning object and final property/relation
     * key. A single-segment path is owned by the context object itself.
     */
    private fun resolveOwnerAndKey(path: String): Pair<DataObject, String> {
        val contextObj = modelQueryProcessor.getDataObjectById(contextObjectId)
        val segments = path.split("->").map { it.trim() }
        return if (segments.size == 1) {
            contextObj to segments[0]
        } else {
            val parentPath = segments.dropLast(1).joinToString("->")
            val parent = modelQueryProcessor.resolvePathFromObject(contextObj, parentPath) as? DataObject
                ?: throw IllegalArgumentException("Cannot navigate to parent for path '$path'")
            parent to segments.last()
        }
    }

    /** Resolves the source object of an object operation; an empty path or "self" means the context object. */
    private fun resolveSource(sourcePath: String): DataObject {
        val contextObj = modelQueryProcessor.getDataObjectById(contextObjectId)
        if (sourcePath.isEmpty() || sourcePath == "self") return contextObj
        return modelQueryProcessor.resolvePathFromObject(contextObj, sourcePath) as? DataObject
            ?: throw IllegalArgumentException("Source path '$sourcePath' does not resolve to an object")
    }

    private fun objectRelationOrThrow(owner: DataObject, relation: String): ClassTypeProperty =
        owner.ofType.objectPropsAsMap()[relation]
            ?: throw IllegalArgumentException(
                "No object relation '$relation' on type '${owner.ofType.name}' (is it a scalar property? use SET/SETLIST)"
            )

    private fun scalarTypeOrThrow(owner: DataObject, key: String): String {
        val propDef = owner.ofType.simplePropsAsMap()[key]
            ?: throw IllegalArgumentException(
                "'$key' on '${owner.ofType.name}' is not a scalar property (use SETOBJ for relations)"
            )
        require(!propDef.isList) { "'$key' is a list property; use SETLIST or APPEND instead of SET" }
        return scalarName(propDef.propertyType)
    }

    private fun simpleListElementTypeOrThrow(owner: DataObject, key: String): String {
        val propDef = owner.ofType.simplePropsAsMap()[key]
            ?: throw IllegalArgumentException(
                "'$key' on '${owner.ofType.name}' is not a simple list property (relations use APPENDOBJ/DROPOBJ)"
            )
        require(propDef.isList) { "'$key' is not a list property; use SET instead of APPEND/SETLIST" }
        return scalarName(propDef.propertyType)
    }

    private fun scalarName(type: SimplePropertyType): String = when (type) {
        SimplePropertyType.NUMBER -> "number"
        SimplePropertyType.BOOLEAN -> "boolean"
        SimplePropertyType.STRING -> "string"
    }

    /**
     * Parses a literal string value into the target type.
     * - "number" → Double, "boolean" → Boolean, "string"/other → the string as-is.
     */
    private fun parseLiteralToType(literal: String, expectedType: String): Any {
        return when (expectedType.lowercase()) {
            "number" -> literal.toDoubleOrNull()
                ?: throw IllegalArgumentException("Cannot parse '$literal' as number")
            "boolean" -> when (literal.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Cannot parse '$literal' as boolean")
            }
            else -> literal
        }
    }
}
