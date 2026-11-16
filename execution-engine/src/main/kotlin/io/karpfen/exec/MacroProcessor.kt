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

import io.karpfen.dsl.instance.*
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.meta.SimpleProperty
import io.karpfen.dsl.meta.SimplePropertyType
import org.json.JSONArray
import org.json.JSONObject
import io.karpfen.dsl.states.Macro
import io.karpfen.io.karpfen.metrics.CodeWay
import io.karpfen.io.karpfen.metrics.RunMetricsRecorder

/**
 * Runs the `EVAL` blocks and macros of one state machine instance in the way their code needs, and reports every call
 * with its way and duration to [metrics]: only the run of the code counts, not the writing of the code before it and
 * not the reading of its result after it.
 *
 * Code that the runtime can compute itself is computed in Kotlin. All other code runs in a Python process of
 * [pythonSessions], which stays open and gives every call a fresh namespace. What the code prints goes to [output],
 * never into its result.
 *
 * @property pythonSessions the Python processes the code runs in. The engine hands every instance of an environment the
 *   same pool and closes it when the environment ends. A processor built without one gets a pool of its own, which its
 *   user ends with [PythonSessionPool.close].
 * @property output receives the text that code printed, with the key of the object of the instance.
 */
class MacroProcessor(
    val metamodel: Metamodel,
    val model: Model,
    val macros: List<Macro>,
    val currentContextModelElement: String,
    val modelQueryProcessor: ModelQueryProcessor,
    private val metrics: RunMetricsRecorder = RunMetricsRecorder.NONE,
    val pythonSessions: PythonSessionPool = PythonSessionPool(::PersistentPythonSession),
    private val output: CodeOutput = CodeOutput.CONSOLE
) {

    private companion object {
        /** The number of characters of a result that an error message shows. */
        const val PREVIEW_LENGTH = 200
    }

    val context: DataObject = modelQueryProcessor.getDataObjectById(currentContextModelElement)
    private val codeTransformer = MacroCodeTransformer(modelQueryProcessor, macros)

    /**
     * Executes an inline macro (EVAL block) with the given code.
     *
     * @param code The raw code from the EVAL block containing $(path) references and @macro calls.
     * @param expectedTarget The expected return type name (e.g., "number", "Vector", "boolean", "string",
     *                       or 'reference("TypeName")').
     * @param eventContext The payload of the event in scope, used to resolve `$(event->...)` paths.
     * @return The result of the Python execution, converted to the appropriate Kotlin/model type.
     *         For primitive types ("number", "boolean", "string"): returns Double, Boolean, or String.
     *         For reference types (reference("X")): returns the existing DataObject looked up by __id__.
     *         For complex metamodel types ("Vector", etc.): returns a DataObject constructed from the JSON dict.
     */
    fun executeInlineMacro(code: String, expectedTarget: String, scope: Map<String, Any?> = emptyMap()): Any? {
        val pythonCode = codeTransformer.transformInlineCode(code, context, scope)
        return executeWithTieredStrategy(pythonCode, expectedTarget)
    }

    /**
     * Runs the finished code text [pythonCode]: in Kotlin when it is [ExecutionTier.SIMPLE] and [simpleResult] gives
     * the result Python would give, and in Python otherwise.
     */
    private fun executeWithTieredStrategy(pythonCode: String, expectedTarget: String): Any? {
        if (ExpressionClassifier.classify(pythonCode) == ExecutionTier.SIMPLE) {
            val start = System.nanoTime()
            val computed = simpleResult(pythonCode, expectedTarget)
            if (computed != null) {
                metrics.codeRan(CodeWay.COMPUTED, System.nanoTime() - start)
                return computed
            }
        }
        val rawResult = timed(CodeWay.OPEN_PROCESS) { runPython(pythonCode) } ?: return null
        return parseResultInModel(rawResult, expectedTarget)
    }

    /** Runs [call] and reports it to [metrics] as a call in the way [way], also when it fails. */
    private inline fun <T> timed(way: CodeWay, call: () -> T): T {
        val start = System.nanoTime()
        try {
            return call()
        } finally {
            metrics.codeRan(way, System.nanoTime() - start)
        }
    }

    /**
     * Computes SIMPLE-tier code in Kotlin, or returns null when Python must run it.
     *
     * The Kotlin result counts only when it is certainly the result Python gives: a boolean for the target `boolean`
     * and a finite number for the target `number`. Everything else goes to Python, which then decides as for any other
     * code: an expression the evaluator does not take (such as a chained comparison), a division or modulo by zero and
     * any other case the evaluator refuses (see [SimpleExpressionEvaluator.NotSimpleException]), a number where a
     * boolean is expected and the other way round, and an infinite result. Every other target goes to Python as well,
     * because the evaluator does not know whether Python would write a number as `4` or as `4.0`. The way the code runs
     * therefore never changes its result or its error.
     */
    private fun simpleResult(pythonCode: String, expectedTarget: String): Any? {
        val body = ExpressionClassifier.extractFunctionBody(pythonCode) ?: return null
        val expr = ExpressionClassifier.extractSimpleReturnExpression(body) ?: return null
        val result = try {
            SimpleExpressionEvaluator.evaluate(expr)
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: SimpleExpressionEvaluator.NotSimpleException) {
            return null
        }
        return when (expectedTarget.lowercase()) {
            "boolean" -> result as? Boolean
            "number" -> (result as? Double)?.takeIf { it.isFinite() }
            else -> null
        }
    }

    /**
     * Parses [rawResult] like [parseResult] while holding the write lock of the model: a result that names an existing
     * object writes its values into that object, and a new object takes a fresh id, so the parse must not interleave
     * with reads or writes of branches on other threads.
     */
    private fun parseResultInModel(rawResult: String, expectedTarget: String): Any? =
        modelQueryProcessor.writeModel { parseResult(rawResult, expectedTarget) }

    /**
     * Executes a full macro by name. The macro's TAKES parameters are resolved from the context
     * model element using the paths provided when the macro was called.
     *
     * @param macroName The name of the macro to execute.
     * @param argPaths The paths (relative to the context object) for each TAKES parameter.
     * @param eventContext The payload of the event in scope, so that an argument path may read
     *                     `event->...` from the event that is currently in scope.
     * @return The result of the macro execution, converted according to the macro's RETURNS directive.
     */
    fun executeFullMacro(macroName: String, argPaths: List<String> = emptyList(), scope: Map<String, Any?> = emptyMap()): Any? {
        val macro = macros.firstOrNull { it.name == macroName }
            ?: throw IllegalArgumentException("Macro '$macroName' not found")

        // Resolve each TAKES parameter from the context object (or a scope binding) using the provided paths
        val resolvedArgs = mutableMapOf<String, String>()
        for (i in macro.takes.indices) {
            val takesDirective = macro.takes[i]
            val argPath = if (i < argPaths.size) argPaths[i] else takesDirective.paramName

            val resolved = modelQueryProcessor.resolvePathInScope(context, scope, argPath)
            resolvedArgs[takesDirective.paramName] = modelQueryProcessor.anyToPythonLiteral(resolved)
        }

        val pythonCode = codeTransformer.transformFullMacroCode(macro, resolvedArgs)
        return executeWithTieredStrategy(pythonCode, macro.returns.returnType)
    }

    /**
     * Runs the complete code text [code] in a Python process of [pythonSessions] and returns its result as text, or
     * null when it left none. What the code printed goes to [output], also when the code failed.
     *
     * @throws PythonExecutionException when the code fails or its process ends during the call.
     */
    fun runPython(code: String): String? {
        val answer = pythonSessions.execute(code)
        if (answer.printed.isNotEmpty()) output.printed(currentContextModelElement, answer.printed)
        answer.error?.let { throw PythonExecutionException.raised(it) }
        return answer.result
    }

    /**
     * Parses the raw string result from Python execution into the expected type.
     *
     * The return value depends on [expectedType]:
     * - "number" → [Double]
     * - "boolean" → [Boolean]
     * - "string" → [String]
     * - reference("TypeName") → [DataObject] (looked up by __id__ from the model)
     * - A metamodel class type name (e.g. "Vector") → [DataObject] constructed from the JSON dict.
     *   If the dict contains an __id__ that matches an existing object, that object's properties
     *   are updated in place. Otherwise a new DataObject is created.
     * - list("TypeName") → [List] of the above
     *
     * A `string` result is the text as the code returned it, white space and line breaks included. For every other
     * type the text is trimmed first, and a text that is then empty is no result. A `number` accepts `nan`, `inf` and
     * `-inf`, as Python prints these floats.
     *
     * @param rawResult The raw stdout string from Python.
     * @param expectedType The expected type string.
     * @return The parsed result.
     */
    fun parseResult(rawResult: String, expectedType: String): Any? {
        if (expectedType.lowercase() == "string") return rawResult
        return parseTrimmedResult(rawResult.trim(), expectedType)
    }

    /** Parses a result of a type other than `string` from its trimmed text [rawResult]; see [parseResult]. */
    private fun parseTrimmedResult(rawResult: String, expectedType: String): Any? {
        if (rawResult.isEmpty()) return null

        // Handle reference types: reference("TypeName") → look up existing DataObject by __id__
        val refMatch = Regex("""reference\("(\w+)"\)""").find(expectedType)
        if (refMatch != null) {
            return parseReferenceResult(rawResult)
        }

        // Handle list types: list("TypeName") → parse JSON array
        val listMatch = Regex("""list\("(\w+)"\)""").find(expectedType)
        if (listMatch != null) {
            val elementType = listMatch.groupValues[1]
            return parseListResult(rawResult, elementType)
        }

        // Handle primitive types
        return when (expectedType.lowercase()) {
            "number" -> {
                parsePythonNumber(rawResult) ?: throw IllegalArgumentException(
                    "Expected number but got: ${preview(rawResult)}"
                )
            }
            "boolean" -> {
                when (rawResult.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("Expected boolean but got: ${preview(rawResult)}")
                }
            }
            else -> {
                // It's a complex metamodel type (like "Vector") — parse JSON dict to DataObject
                parseComplexResult(rawResult, expectedType)
            }
        }
    }

    // ---- Result parsing helpers ----

    /**
     * [text] for an error message: as it is up to [PREVIEW_LENGTH] characters, otherwise its beginning followed by
     * `...`, so that a long result does not fill the trace log.
     */
    private fun preview(text: String): String =
        if (text.length <= PREVIEW_LENGTH) text else text.take(PREVIEW_LENGTH) + "..."

    /** Reads a number as Python prints a float or an int, `nan`, `inf` and `-inf` included, or null. */
    private fun parsePythonNumber(text: String): Double? = when (text) {
        "nan", "-nan" -> Double.NaN
        "inf" -> Double.POSITIVE_INFINITY
        "-inf" -> Double.NEGATIVE_INFINITY
        else -> text.toDoubleOrNull()
    }

    /**
     * Parses a reference result. The Python code returns either:
     * - A JSON dict containing "__id__": the existing DataObject with that id. When the dict has entries besides
     *   `__id__` and `__type__`, the object is changed in place first, as [jsonObjectToDataObject] describes, so the
     *   rule for a dictionary with the id of an existing object holds for `knows` relations as for `has` relations.
     * - A plain string id → look up directly.
     */
    private fun parseReferenceResult(rawResult: String): DataObject {
        val trimmed = rawResult.trim()
        if (trimmed.startsWith("{")) {
            val json = JSONObject(trimmed)
            if (json.has("__id__")) {
                val existing = modelQueryProcessor.getDataObjectById(json.getString("__id__"))
                val changes = json.keySet().any { it != "__id__" && it != "__type__" }
                return if (changes) jsonObjectToDataObject(json, existing.ofType.name) else existing
            }
        }
        val cleanId = trimmed.trim('"', '\'', ' ')
        return modelQueryProcessor.getDataObjectById(cleanId)
    }

    /**
     * Parses a JSON array result into a list. Each element is parsed according to [elementType].
     * If [elementType] is a metamodel class type, each element becomes a DataObject.
     * If it is a primitive type, each element becomes a primitive.
     */
    private fun parseListResult(rawResult: String, elementType: String): List<Any> {
        val trimmed = rawResult.trim()
        val jsonArray = JSONArray(trimmed)
        val results = mutableListOf<Any>()
        for (i in 0 until jsonArray.length()) {
            val element = jsonArray.get(i)
            when (elementType.lowercase()) {
                "number" -> results.add((element as Number).toDouble())
                "boolean" -> results.add(element as Boolean)
                "string" -> results.add(element.toString())
                else -> {
                    // Complex type — parse the JSONObject to a DataObject
                    val jsonObj = jsonArray.getJSONObject(i)
                    results.add(jsonObjectToDataObject(jsonObj, elementType))
                }
            }
        }
        return results
    }

    /**
     * Parses a complex result (JSON dict) for a metamodel class type.
     *
     * Strategy:
     * 1. If the dict has an "__id__" field and an object with that id exists in the model,
     *    change that existing object in place, as [jsonObjectToDataObject] describes, and return it.
     * 2. Otherwise, construct a new DataObject from the dict, populating simple properties
     *    from the JSON values and resolving nested dicts/references for object properties.
     *
     * @return A DataObject (either existing-and-updated, or newly constructed).
     */
    private fun parseComplexResult(rawResult: String, typeName: String): DataObject {
        val trimmed = rawResult.trim()
        if (!trimmed.startsWith("{")) {
            throw IllegalArgumentException("Expected a JSON dict for type '$typeName' but got: ${preview(rawResult)}")
        }
        val json = JSONObject(trimmed)
        return jsonObjectToDataObject(json, typeName)
    }

    /**
     * Converts a [JSONObject] into a [DataObject].
     *
     * If the JSON contains "__id__" and an object with that id already exists in the model, that object is changed
     * in place through [ModelQueryProcessor.updateObjectInPlace] with the properties and relations of the JSON:
     * objects its `has` relations no longer hold leave the model like through `DROPOBJ`, objects they newly hold
     * enter it as new objects, and the change is published. Nested dictionaries are converted first, so a nested
     * existing object is changed before the object that holds it.
     *
     * If no existing object is found, a new DataObject is built from scratch using the metamodel
     * type definition to determine which keys are simple properties vs. object properties.
     */
    private fun jsonObjectToDataObject(json: JSONObject, typeName: String): DataObject {
        // If the JSON carries an __id__, check whether this object already exists in the model.
        if (json.has("__id__")) {
            val existingId = json.getString("__id__")
            val found = try {
                modelQueryProcessor.getDataObjectById(existingId)
            } catch (_: IllegalArgumentException) {
                null
            }
            found?.let { existing ->
                val propsUpdate = extractSimplePropsFromJson(json, existing.ofType)
                val relsUpdate = extractRelationsFromJson(json, existing.ofType)
                modelQueryProcessor.updateObjectInPlace(existing, propsUpdate, relsUpdate)
                return existing
            }
        }

        // Construct a new DataObject from scratch
        val classType = metamodel.getTypeByName(typeName)
            ?: throw IllegalArgumentException("Unknown metamodel type '$typeName' for JSON result parsing")

        // A macro that builds a brand-new object (no __id__) gets a fresh model-unique id assigned here,
        // so that its child object references carry real ids and it can be registered into the model.
        val id = if (json.has("__id__")) json.getString("__id__") else modelQueryProcessor.generateUniqueId(typeName)
        val simpleProps = buildSimplePropertyObjects(json, classType)
        val relations = buildClassTypePropertyObjects(json, classType)

        return DataObject(classType, id, simpleProps, relations)
    }

    /**
     * Extracts simple (primitive) property values from a JSONObject
     * according to the metamodel ClassType definition.
     *
     * @return A map of property key → typed value, suitable for [DataObject.assignProps].
     */
    private fun extractSimplePropsFromJson(json: JSONObject, classType: ClassType): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for (prop in classType.simpleProperties) {
            if (!json.has(prop.key)) continue
            if (json.isNull(prop.key)) continue

            if (prop.isList) {
                val arr = json.getJSONArray(prop.key)
                val values = mutableListOf<Any>()
                for (i in 0 until arr.length()) {
                    values.add(parseSimpleValue(arr.get(i), prop))
                }
                result[prop.key] = values
            } else {
                result[prop.key] = parseSimpleValue(json.get(prop.key), prop)
            }
        }
        return result
    }

    /**
     * Extracts object (relation) property values from a JSONObject.
     * Each nested JSON object is recursively converted to a DataObject.
     *
     * @return A map of relation key → DataObject (or List<DataObject>),
     *         suitable for [DataObject.assignRels].
     */
    private fun extractRelationsFromJson(json: JSONObject, classType: ClassType): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for (objProp in classType.objectProperties) {
            if (!json.has(objProp.key)) continue
            if (json.isNull(objProp.key)) continue

            val targetTypeName = objProp.reference.classTypeName

            if (objProp.isList) {
                val arr = json.getJSONArray(objProp.key)
                val objects = mutableListOf<DataObject>()
                for (i in 0 until arr.length()) {
                    objects.add(jsonObjectToDataObject(arr.getJSONObject(i), targetTypeName))
                }
                result[objProp.key] = objects
            } else {
                val nestedJson = json.getJSONObject(objProp.key)
                result[objProp.key] = jsonObjectToDataObject(nestedJson, targetTypeName)
            }
        }
        return result
    }

    /**
     * Builds a list of [SimplePropertyObject] instances from a JSONObject
     * for constructing a new DataObject.
     */
    private fun buildSimplePropertyObjects(json: JSONObject, classType: ClassType): MutableList<SimplePropertyObject> {
        val result = mutableListOf<SimplePropertyObject>()
        for (prop in classType.simpleProperties) {
            if (!json.has(prop.key)) {
                // Create with default values
                if (prop.isList) {
                    result.add(SimpleListPropertyObject(prop, prop.key, mutableListOf()))
                } else {
                    val default = defaultValueForType(prop.propertyType)
                    result.add(SimpleAtomicPropertyObject(prop, prop.key, default))
                }
                continue
            }
            if (json.isNull(prop.key)) continue

            if (prop.isList) {
                val arr = json.getJSONArray(prop.key)
                val values = mutableListOf<Any>()
                for (i in 0 until arr.length()) {
                    values.add(parseSimpleValue(arr.get(i), prop))
                }
                result.add(SimpleListPropertyObject(prop, prop.key, values))
            } else {
                val value = parseSimpleValue(json.get(prop.key), prop)
                result.add(SimpleAtomicPropertyObject(prop, prop.key, value))
            }
        }
        return result
    }

    /**
     * Builds a list of [ClassTypePropertyObject] instances from a JSONObject
     * for constructing a new DataObject.
     */
    private fun buildClassTypePropertyObjects(
        json: JSONObject,
        classType: ClassType
    ): MutableList<ClassTypePropertyObject> {
        val result = mutableListOf<ClassTypePropertyObject>()
        for (objProp in classType.objectProperties) {
            val targetTypeName = objProp.reference.classTypeName

            if (!json.has(objProp.key) || json.isNull(objProp.key)) {
                // Create empty placeholder
                if (objProp.isList) {
                    result.add(ClassTypeListPropertyObject(objProp, objProp.key, mutableListOf()))
                } else {
                    result.add(ClassTypeAtomicPropertyObject(objProp, objProp.key, ObjectReference("")))
                }
                continue
            }

            if (objProp.isList) {
                val arr = json.getJSONArray(objProp.key)
                val refs = mutableListOf<ObjectReference>()
                for (i in 0 until arr.length()) {
                    val child = jsonObjectToDataObject(arr.getJSONObject(i), targetTypeName)
                    refs.add(ObjectReference(child.id, child))
                }
                result.add(ClassTypeListPropertyObject(objProp, objProp.key, refs))
            } else {
                val nestedJson = json.getJSONObject(objProp.key)
                val child = jsonObjectToDataObject(nestedJson, targetTypeName)
                result.add(ClassTypeAtomicPropertyObject(objProp, objProp.key, ObjectReference(child.id, child)))
            }
        }
        return result
    }

    /**
     * Parses a single raw JSON value into the correct Kotlin type
     * according to the metamodel [SimpleProperty] definition.
     */
    private fun parseSimpleValue(raw: Any, prop: SimpleProperty): Any {
        return when (prop.propertyType) {
            SimplePropertyType.NUMBER -> (raw as Number).toDouble()
            SimplePropertyType.BOOLEAN -> raw as Boolean
            SimplePropertyType.STRING -> raw.toString()
        }
    }

    /**
     * Returns a sensible default value for a given primitive type.
     */
    private fun defaultValueForType(type: SimplePropertyType): Any {
        return when (type) {
            SimplePropertyType.NUMBER -> 0.0
            SimplePropertyType.BOOLEAN -> false
            SimplePropertyType.STRING -> ""
        }
    }
}