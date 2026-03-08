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
import states.Macro

/**
 * Transforms macro code from the karpfen DSL syntax into executable Python code.
 *
 * Handles:
 * - $(path->to->prop) model references → replaced with concrete Python values
 * - @macroName(args) macro calls → inlined as Python function definitions + calls
 */
class MacroCodeTransformer(
    private val modelQueryProcessor: ModelQueryProcessor,
    private val macros: List<Macro>
) {

    companion object {
        /** Regex for matching $(path) model variable references */
        val MODEL_REF_PATTERN = Regex("""\$\(([^)]+)\)""")

        /** Regex for matching @macroName(arg1, arg2, ...) macro calls */
        val MACRO_CALL_PATTERN = Regex("""@(\w+)\(([^)]*)\)""")
    }

    /**
     * Transforms inline macro code (EVAL blocks) into executable Python code.
     *
     * @param code The raw code from the EVAL block
     * @param contextObj The DataObject that serves as the context for $(path) resolution
     * @return A complete Python script string ready for execution
     */
    fun transformInlineCode(code: String, contextObj: DataObject): String {
        val functionDefs = mutableListOf<String>()
        val resolvedCode = resolveCode(code, contextObj, functionDefs, mutableSetOf())
        val rewrittenCode = PythonIndentationRewriter.rewrite(resolvedCode, baseDepth = 1)
        val sb = StringBuilder()
        sb.appendLine("import json")
        sb.appendLine()

        // Add all dependent macro function definitions
        for (funcDef in functionDefs) {
            sb.appendLine(funcDef)
            sb.appendLine()
        }

        // Wrap the inline code in a main block that prints the result as JSON
        sb.appendLine("def __karpfen_main__():")
        sb.appendLine(rewrittenCode)
        sb.appendLine()
        sb.appendLine("__result__ = __karpfen_main__()")
        sb.appendLine("if __result__ is not None:")
        sb.appendLine("    if isinstance(__result__, dict):")
        sb.appendLine("        print(json.dumps(__result__))")
        sb.appendLine("    elif isinstance(__result__, list):")
        sb.appendLine("        print(json.dumps(__result__))")
        sb.appendLine("    elif isinstance(__result__, bool):")
        sb.appendLine("        print(json.dumps(__result__))")
        sb.appendLine("    else:")
        sb.appendLine("        print(__result__)")

        return sb.toString()
    }

    /**
     * Transforms a full macro definition into executable Python code.
     *
     * @param macro The macro definition
     * @param resolvedArgs Map of parameter names to their resolved values (already Python literals)
     * @return A complete Python script string ready for execution
     */
    fun transformFullMacroCode(macro: Macro, resolvedArgs: Map<String, String>): String {
        val functionDefs = mutableListOf<String>()
        val code = macro.definition.codeBlock.code

        val resolvedCode = resolveFullMacroCode(code, macro, resolvedArgs, functionDefs, mutableSetOf())
        val rewrittenCode = PythonIndentationRewriter.rewrite(resolvedCode, baseDepth = 1)

        val sb = StringBuilder()
        sb.appendLine("import json")
        sb.appendLine()

        for (funcDef in functionDefs) {
            sb.appendLine(funcDef)
            sb.appendLine()
        }

        sb.appendLine("def __karpfen_main__():")
        sb.appendLine(rewrittenCode)
        sb.appendLine()
        sb.appendLine("__result__ = __karpfen_main__()")
        sb.appendLine("if __result__ is not None:")
        sb.appendLine("    if isinstance(__result__, dict):")
        sb.appendLine("        print(json.dumps(__result__))")
        sb.appendLine("    elif isinstance(__result__, list):")
        sb.appendLine("        print(json.dumps(__result__))")
        sb.appendLine("    elif isinstance(__result__, bool):")
        sb.appendLine("        print(json.dumps(__result__))")
        sb.appendLine("    else:")
        sb.appendLine("        print(__result__)")

        return sb.toString()
    }

    /**
     * Resolves model references $(path) and macro calls @name(args) in code,
     * using a context DataObject for path resolution.
     */
    private fun resolveCode(
        code: String,
        contextObj: DataObject,
        functionDefs: MutableList<String>,
        alreadyResolved: MutableSet<String>
    ): String {
        var result = code

        // First, resolve @macroName(args) calls — these need to be turned into function definitions + calls
        result = resolveMacroCalls(result, contextObj, functionDefs, alreadyResolved)

        // Then, resolve $(path) model references
        result = resolveModelReferences(result, contextObj)

        return result
    }

    /**
     * Resolves model references $(path) in the code for a full macro,
     * where $(paramName) or $(paramName->sub->path) refers to TAKES parameters.
     */
    private fun resolveFullMacroCode(
        code: String,
        macro: Macro,
        resolvedArgs: Map<String, String>,
        functionDefs: MutableList<String>,
        alreadyResolved: MutableSet<String>
    ): String {
        var result = code

        // Resolve @macroName(args) calls first
        result = resolveFullMacroMacroCalls(result, macro, resolvedArgs, functionDefs, alreadyResolved)

        // Resolve $(paramName) and $(paramName->path) using the pre-resolved args
        result = MODEL_REF_PATTERN.replace(result) { matchResult ->
            val path = matchResult.groupValues[1].trim()
            val segments = path.split("->").map { it.trim() }
            val paramName = segments[0]

            if (resolvedArgs.containsKey(paramName)) {
                if (segments.size == 1) {
                    // Direct parameter reference
                    resolvedArgs[paramName]!!
                } else {
                    // Navigating into the parameter — the arg value is a Python dict,
                    // so we generate dict access: paramName["key1"]["key2"]...
                    val varName = "__param_${paramName}__"
                    val accessChain = segments.drop(1).joinToString("") { "[\"$it\"]" }
                    "${varName}${accessChain}"
                }
            } else {
                // Not a parameter — treat as a local Python variable reference.
                // e.g., $(obstacle) → obstacle, $(obstacle->boundingBox->position) → obstacle["boundingBox"]["position"]
                if (segments.size == 1) {
                    paramName
                } else {
                    val accessChain = segments.drop(1).joinToString("") { "[\"$it\"]" }
                    "$paramName$accessChain"
                }
            }
        }

        // If there were navigated parameters, we need to add variable assignments at the top
        val paramAssignments = mutableListOf<String>()
        for ((paramName, paramValue) in resolvedArgs) {
            // Check if the code uses __param_paramName__ (i.e., it navigates into the parameter)
            val varName = "__param_${paramName}__"
            if (result.contains(varName)) {
                paramAssignments.add("$varName = $paramValue")
            }
        }

        if (paramAssignments.isNotEmpty()) {
            result = paramAssignments.joinToString("\n") + "\n" + result
        }

        return result
    }

    /**
     * Resolves $(path) model references by looking up values in the context object.
     */
    private fun resolveModelReferences(code: String, contextObj: DataObject): String {
        return MODEL_REF_PATTERN.replace(code) { matchResult ->
            val path = matchResult.groupValues[1].trim()
            val resolved = modelQueryProcessor.resolvePathFromObject(contextObj, path)
            modelQueryProcessor.anyToPythonLiteral(resolved)
        }
    }

    /**
     * Resolves @macroName(args) calls in inline code by generating Python function definitions.
     */
    private fun resolveMacroCalls(
        code: String,
        contextObj: DataObject,
        functionDefs: MutableList<String>,
        alreadyResolved: MutableSet<String>
    ): String {
        return MACRO_CALL_PATTERN.replace(code) { matchResult ->
            val macroName = matchResult.groupValues[1]
            val argsString = matchResult.groupValues[2].trim()

            val macro = macros.firstOrNull { it.name == macroName }
                ?: throw IllegalArgumentException("Macro '$macroName' not found")

            // Generate the function definition if not already generated
            if (macroName !in alreadyResolved) {
                alreadyResolved.add(macroName)
                val funcDef = generatePythonFunction(macro, contextObj, functionDefs, alreadyResolved)
                functionDefs.add(funcDef)
            }

            // Parse the arguments from the call — these are already resolved $(path) expressions
            // in the caller's context; they'll be passed as Python expressions
            val args = if (argsString.isEmpty()) emptyList()
            else splitMacroCallArgs(argsString)

            // Resolve each argument expression
            val resolvedArgs = args.map { arg ->
                val trimmed = arg.trim()
                // Check if this is a $(path) reference
                val refMatch = MODEL_REF_PATTERN.find(trimmed)
                if (refMatch != null && refMatch.value == trimmed) {
                    val path = refMatch.groupValues[1].trim()
                    val resolved = modelQueryProcessor.resolvePathFromObject(contextObj, path)
                    modelQueryProcessor.anyToPythonLiteral(resolved)
                } else {
                    trimmed
                }
            }

            "$macroName(${resolvedArgs.joinToString(", ")})"
        }
    }

    /**
     * Resolves @macroName(args) calls in full macro code.
     */
    private fun resolveFullMacroMacroCalls(
        code: String,
        parentMacro: Macro,
        parentResolvedArgs: Map<String, String>,
        functionDefs: MutableList<String>,
        alreadyResolved: MutableSet<String>
    ): String {
        return MACRO_CALL_PATTERN.replace(code) { matchResult ->
            val macroName = matchResult.groupValues[1]
            val argsString = matchResult.groupValues[2].trim()

            val macro = macros.firstOrNull { it.name == macroName }
                ?: throw IllegalArgumentException("Macro '$macroName' not found")

            if (macroName !in alreadyResolved) {
                alreadyResolved.add(macroName)
                val funcDef = generatePythonFunctionForFullMacro(macro, functionDefs, alreadyResolved)
                functionDefs.add(funcDef)
            }

            // Parse and resolve arguments
            val args = if (argsString.isEmpty()) emptyList()
            else splitMacroCallArgs(argsString)

            val resolvedCallArgs = args.map { arg ->
                val trimmed = arg.trim()
                val refMatch = MODEL_REF_PATTERN.find(trimmed)
                if (refMatch != null && refMatch.value == trimmed) {
                    val path = refMatch.groupValues[1].trim()
                    val segments = path.split("->").map { it.trim() }
                    val paramName = segments[0]

                    if (parentResolvedArgs.containsKey(paramName)) {
                        if (segments.size == 1) {
                            parentResolvedArgs[paramName]!!
                        } else {
                            val varName = "__param_${paramName}__"
                            val accessChain = segments.drop(1).joinToString("") { "[\"$it\"]" }
                            "${varName}${accessChain}"
                        }
                    } else {
                        // Local Python variable reference
                        if (segments.size == 1) {
                            paramName
                        } else {
                            val accessChain = segments.drop(1).joinToString("") { "[\"$it\"]" }
                            "$paramName$accessChain"
                        }
                    }
                } else {
                    trimmed
                }
            }

            "$macroName(${resolvedCallArgs.joinToString(", ")})"
        }
    }

    /**
     * Generates a Python function definition for a macro, resolving its internal
     * $(path) references and @macro calls recursively.
     * This version is used for inline code contexts where a contextObj is available.
     */
    private fun generatePythonFunction(
        macro: Macro,
        contextObj: DataObject,
        functionDefs: MutableList<String>,
        alreadyResolved: MutableSet<String>
    ): String {
        val params = macro.takes.joinToString(", ") { it.paramName }
        val body = macro.definition.codeBlock.code

        // For the macro body, $(paramName) references refer to the macro's parameters.
        // We need to replace them with Python variable references.
        val resolvedBody = resolveMacroBodyForFunction(body, macro, functionDefs, alreadyResolved)
        val rewrittenBody = PythonIndentationRewriter.rewrite(resolvedBody, baseDepth = 1)

        val sb = StringBuilder()
        sb.appendLine("def ${macro.name}($params):")
        sb.append(rewrittenBody)
        return sb.toString()
    }

    /**
     * Generates a Python function definition for a macro used within a full macro context.
     */
    private fun generatePythonFunctionForFullMacro(
        macro: Macro,
        functionDefs: MutableList<String>,
        alreadyResolved: MutableSet<String>
    ): String {
        val params = macro.takes.joinToString(", ") { it.paramName }
        val body = macro.definition.codeBlock.code

        val resolvedBody = resolveMacroBodyForFunction(body, macro, functionDefs, alreadyResolved)
        val rewrittenBody = PythonIndentationRewriter.rewrite(resolvedBody, baseDepth = 1)

        val sb = StringBuilder()
        sb.appendLine("def ${macro.name}($params):")
        sb.append(rewrittenBody)
        return sb.toString()
    }

    /**
     * Resolves the body of a macro function definition. $(param) references become
     * Python variable accesses, and @macro calls are recursively resolved.
     */
    private fun resolveMacroBodyForFunction(
        body: String,
        macro: Macro,
        functionDefs: MutableList<String>,
        alreadyResolved: MutableSet<String>
    ): String {
        val paramNames = macro.takes.map { it.paramName }.toSet()

        // First resolve @macro calls
        var result = MACRO_CALL_PATTERN.replace(body) { matchResult ->
            val calledMacroName = matchResult.groupValues[1]
            val argsString = matchResult.groupValues[2].trim()

            val calledMacro = macros.firstOrNull { it.name == calledMacroName }
                ?: throw IllegalArgumentException("Macro '$calledMacroName' not found")

            if (calledMacroName !in alreadyResolved) {
                alreadyResolved.add(calledMacroName)
                val funcDef = generatePythonFunctionForFullMacro(calledMacro, functionDefs, alreadyResolved)
                functionDefs.add(funcDef)
            }

            // Arguments in a macro call within a macro body: they are $(param->path) references
            // which refer to the enclosing macro's parameters
            val args = if (argsString.isEmpty()) emptyList()
            else splitMacroCallArgs(argsString)

            val resolvedCallArgs = args.map { arg ->
                val trimmed = arg.trim()
                val refMatch = MODEL_REF_PATTERN.find(trimmed)
                if (refMatch != null && refMatch.value == trimmed) {
                    val path = refMatch.groupValues[1].trim()
                    resolveParamPath(path, paramNames)
                } else {
                    trimmed
                }
            }

            "$calledMacroName(${resolvedCallArgs.joinToString(", ")})"
        }

        // Then resolve $(param) and $(param->path) references
        result = MODEL_REF_PATTERN.replace(result) { matchResult ->
            val path = matchResult.groupValues[1].trim()
            resolveParamPath(path, paramNames)
        }

        return result
    }

    /**
     * Resolves a path expression that refers to a macro parameter or a local Python variable.
     * "paramName" → paramName (Python variable)
     * "paramName->sub->path" → paramName["sub"]["path"] (Python dict access)
     *
     * If the first segment is not a known parameter, it is treated as a local Python variable
     * (e.g., a loop variable like "obstacle" in "for obstacle in $(obstacles)").
     * In that case, the same dict-access pattern is used.
     */
    private fun resolveParamPath(path: String, paramNames: Set<String>): String {
        val segments = path.split("->").map { it.trim() }
        val firstSegment = segments[0]

        // Both known parameters and unknown identifiers (local Python variables) are
        // treated the same way: as Python variable names with optional dict access.
        return if (segments.size == 1) {
            firstSegment
        } else {
            val accessChain = segments.drop(1).joinToString("") { "[\"$it\"]" }
            "$firstSegment$accessChain"
        }
    }

    /**
     * Splits macro call arguments, handling nested parentheses and dict literals.
     */
    private fun splitMacroCallArgs(argsString: String): List<String> {
        val args = mutableListOf<String>()
        var depth = 0
        var braceDepth = 0
        val current = StringBuilder()

        for (ch in argsString) {
            when (ch) {
                '(' -> { depth++; current.append(ch) }
                ')' -> { depth--; current.append(ch) }
                '{' -> { braceDepth++; current.append(ch) }
                '}' -> { braceDepth--; current.append(ch) }
                ',' -> {
                    if (depth == 0 && braceDepth == 0) {
                        args.add(current.toString().trim())
                        current.clear()
                    } else {
                        current.append(ch)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) {
            args.add(current.toString().trim())
        }
        return args
    }
}

