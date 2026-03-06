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

import instance.*
import meta.ClassType
import meta.Metamodel
import meta.SimpleProperty
import meta.SimplePropertyType
import org.json.JSONArray
import org.json.JSONObject
import states.Macro
import java.nio.file.Files

class MacroProcessor(
    val metamodel: Metamodel,
    val model: Model,
    val macros: List<Macro>,
    val currentContextModelElement: String,
    val modelQueryProcessor: ModelQueryProcessor
) {

    val context: DataObject = modelQueryProcessor.getDataObjectById(currentContextModelElement)
    private val codeTransformer = MacroCodeTransformer(modelQueryProcessor, macros)

    /**
     * Executes an inline macro (EVAL block) with the given code.
     *
     * @param code The raw code from the EVAL block containing $(path) references and @macro calls.
     * @param expectedTarget The expected return type name (e.g., "number", "Vector", "boolean", "string",
     *                       or 'reference("TypeName")').
     * @return The result of the Python execution, converted to the appropriate Kotlin/model type.
     *         For primitive types ("number", "boolean", "string"): returns Double, Boolean, or String.
     *         For reference types (reference("X")): returns the existing DataObject looked up by __id__.
     *         For complex metamodel types ("Vector", etc.): returns a DataObject constructed from the JSON dict.
     */
    fun executeInlineMacro(code: String, expectedTarget: String): Any? {
        val pythonCode = codeTransformer.transformInlineCode(code, context)
        val rawResult = runPythonCode(pythonCode) ?: return null
        return parseResult(rawResult.toString(), expectedTarget)
    }

    /**
     * Executes a full macro by name. The macro's TAKES parameters are resolved from the context
     * model element using the paths provided when the macro was called.
     *
     * @param macroName The name of the macro to execute.
     * @param argPaths The paths (relative to the context object) for each TAKES parameter.
     * @return The result of the macro execution, converted according to the macro's RETURNS directive.
     */
    fun executeFullMacro(macroName: String, argPaths: List<String> = emptyList()): Any? {
        val macro = macros.firstOrNull { it.name == macroName }
            ?: throw IllegalArgumentException("Macro '$macroName' not found")

        // Resolve each TAKES parameter from the context object using the provided paths
        val resolvedArgs = mutableMapOf<String, String>()
        for (i in macro.takes.indices) {
            val takesDirective = macro.takes[i]
            val argPath = if (i < argPaths.size) argPaths[i] else takesDirective.paramName

            val resolved = modelQueryProcessor.resolvePathFromObject(context, argPath)
            resolvedArgs[takesDirective.paramName] = modelQueryProcessor.anyToPythonLiteral(resolved)
        }

        val pythonCode = codeTransformer.transformFullMacroCode(macro, resolvedArgs)
        val rawResult = runPythonCode(pythonCode) ?: return null
        return parseResult(rawResult.toString(), macro.returns.returnType)
    }

    /**
     * Runs the given Python code as a subprocess and returns the result captured from stdout.
     *
     * The Python code is written to a temporary file and executed via the system's Python interpreter.
     * The result is read from stdout. Errors from stderr result in an exception.
     *
     * @param code The complete Python code to execute.
     * @return The stdout output as a trimmed string, or null if no output.
     */
    fun runPythonCode(code: String): Any? {
        val tempFile = Files.createTempFile("karpfen_macro_", ".py").toFile()
        try {
            tempFile.writeText(code)

            val pythonCommand = findPythonCommand()
            val command = listOf(pythonCommand, tempFile.absolutePath)

            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(false)
            processBuilder.environment()["PYTHONIOENCODING"] = "utf-8"

            val process = processBuilder.start()

            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()

            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw RuntimeException(
                    "Python execution failed with exit code $exitCode.\nStderr: $stderr\nCode:\n$code"
                )
            }

            if (stderr.isNotEmpty()) {
                System.err.println("Python stderr (non-fatal): $stderr")
            }

            return if (stdout.isEmpty()) null else stdout
        } finally {
            tempFile.delete()
        }
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
     * @param rawResult The raw stdout string from Python.
     * @param expectedType The expected type string.
     * @return The parsed result.
     */
    fun parseResult(rawResult: String, expectedType: String): Any? {
        if (rawResult.isBlank()) return null

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
                rawResult.toDoubleOrNull() ?: throw IllegalArgumentException(
                    "Expected number but got: $rawResult"
                )
            }
            "boolean" -> {
                when (rawResult.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("Expected boolean but got: $rawResult")
                }
            }
            "string" -> rawResult
            else -> {
                // It's a complex metamodel type (like "Vector") — parse JSON dict to DataObject
                parseComplexResult(rawResult, expectedType)
            }
        }
    }

    // ---- Result parsing helpers ----

    /**
     * Parses a reference result. The Python code returns either:
     * - A JSON dict containing "__id__" → look up the existing DataObject in the model.
     * - A plain string id → look up directly.
     */
    private fun parseReferenceResult(rawResult: String): DataObject {
        val trimmed = rawResult.trim()
        if (trimmed.startsWith("{")) {
            val json = JSONObject(trimmed)
            if (json.has("__id__")) {
                return modelQueryProcessor.getDataObjectById(json.getString("__id__"))
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
     *    update that existing object's simple properties in place and return it.
     * 2. Otherwise, construct a new DataObject from the dict, populating simple properties
     *    from the JSON values and resolving nested dicts/references for object properties.
     *
     * @return A DataObject (either existing-and-updated, or newly constructed).
     */
    private fun parseComplexResult(rawResult: String, typeName: String): DataObject {
        val trimmed = rawResult.trim()
        if (!trimmed.startsWith("{")) {
            throw IllegalArgumentException("Expected a JSON dict for type '$typeName' but got: $rawResult")
        }
        val json = JSONObject(trimmed)
        return jsonObjectToDataObject(json, typeName)
    }

    /**
     * Converts a [JSONObject] into a [DataObject].
     *
     * If the JSON contains "__id__" and an object with that id already exists in the model,
     * the existing object's simple properties are updated in place with the values from the JSON.
     * This makes sense because macros are side-effect-free: we only propagate values back,
     * and the caller can decide whether to apply the update to the model.
     *
     * If no existing object is found, a new DataObject is built from scratch using the metamodel
     * type definition to determine which keys are simple properties vs. object properties.
     */
    private fun jsonObjectToDataObject(json: JSONObject, typeName: String): DataObject {
        // If the JSON carries an __id__, check whether this object already exists in the model.
        if (json.has("__id__")) {
            val existingId = json.getString("__id__")
            try {
                val existing = modelQueryProcessor.getDataObjectById(existingId)
                // Update the existing object's simple properties with the JSON values
                val propsUpdate = extractSimplePropsFromJson(json, existing.ofType)
                existing.assignProps(propsUpdate)
                // Also handle nested object properties if present
                val relsUpdate = extractRelationsFromJson(json, existing.ofType)
                if (relsUpdate.isNotEmpty()) {
                    existing.assignRels(relsUpdate)
                }
                return existing
            } catch (_: IllegalArgumentException) {
                // Object not found in model — fall through to construct a new one
            }
        }

        // Construct a new DataObject from scratch
        val classType = metamodel.getTypeByName(typeName)
            ?: throw IllegalArgumentException("Unknown metamodel type '$typeName' for JSON result parsing")

        val id = if (json.has("__id__")) json.getString("__id__") else ""
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

    /**
     * Finds the Python command available on the system.
     * Tries 'python3' first, then 'python'.
     */
    private fun findPythonCommand(): String {
        try {
            val process = ProcessBuilder("python3", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            if (process.exitValue() == 0) return "python3"
        } catch (_: Exception) { }

        try {
            val process = ProcessBuilder("python", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            if (process.exitValue() == 0) return "python"
        } catch (_: Exception) { }

        throw RuntimeException(
            "No Python interpreter found. Please ensure 'python3' or 'python' is available on the system PATH."
        )
    }

}