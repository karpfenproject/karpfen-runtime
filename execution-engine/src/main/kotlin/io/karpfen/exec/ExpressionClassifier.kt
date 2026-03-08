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

/**
 * Execution tiers for macro code, ordered from fastest to slowest.
 *
 * - [SIMPLE]: Pure arithmetic/boolean expression — evaluated natively in Kotlin.
 * - [MEDIUM]: Self-contained Python code without imports — executed via `python -c` (no temp file).
 * - [COMPLEX]: Code with imports, function definitions, or large size — executed via
 *   a persistent interactive Python session.
 */
enum class ExecutionTier {
    SIMPLE, MEDIUM, COMPLEX
}

/**
 * Classifies transformed Python code (output of [MacroCodeTransformer]) into an [ExecutionTier]
 * to determine the most efficient execution strategy.
 *
 * The classifier operates on the fully transformed Python code where all `$(path)` model references
 * have already been resolved to concrete literal values.
 */
object ExpressionClassifier {

    /**
     * Tokens allowed in a SIMPLE expression: numeric literals, boolean literals,
     * arithmetic operators, comparison operators, boolean keywords, and parentheses.
     *
     * Matches against the extracted return expression (after stripping the wrapper).
     */
    private val SIMPLE_EXPRESSION_PATTERN = Regex(
        """^[\s\d.eE+\-*/%()<>=!andortTrueFls,]+$"""
    )

    /**
     * More precise validation: the expression must consist only of:
     * - Numeric literals (incl. scientific notation)
     * - Boolean literals (True, False)
     * - Arithmetic operators: + - * / % ** //
     * - Comparison operators: < > <= >= == !=
     * - Boolean keywords: and or not
     * - Parentheses: ( )
     * - Whitespace
     */
    private val SIMPLE_TOKEN_PATTERN = Regex(
        """(\d+\.?\d*(?:[eE][+-]?\d+)?|True|False|and|or|not|[+\-*/%<>=!()]+|\s+)"""
    )

    /** Patterns that indicate COMPLEX code (needs full Python with imports). */
    private val IMPORT_PATTERN = Regex("""^\s*import\s+""", RegexOption.MULTILINE)
    private val FROM_IMPORT_PATTERN = Regex("""^\s*from\s+\w+\s+import\s+""", RegexOption.MULTILINE)
    private val DEF_PATTERN = Regex("""^\s*def\s+""", RegexOption.MULTILINE)

    /** Maximum code length for MEDIUM tier (python -c has command-line length limits). */
    private const val MEDIUM_MAX_LENGTH = 8000

    /**
     * Classifies the given transformed Python code into an [ExecutionTier].
     *
     * @param transformedCode The complete Python script as produced by [MacroCodeTransformer].
     * @return The appropriate execution tier.
     */
    fun classify(transformedCode: String): ExecutionTier {
        val body = extractFunctionBody(transformedCode) ?: return ExecutionTier.COMPLEX

        // Check if body is a single return statement with a simple expression
        val simpleExpr = extractSimpleReturnExpression(body)
        if (simpleExpr != null && isSimpleExpression(simpleExpr)) {
            return ExecutionTier.SIMPLE
        }

        // Extract user-authored code (imports + helper functions) that appears between
        // the standard `import json` and `def __karpfen_main__():`. The standard
        // boilerplate is always present and should not trigger COMPLEX classification.
        val userPreamble = extractUserPreamble(transformedCode)

        // Check for user-defined import/def statements → COMPLEX
        if (IMPORT_PATTERN.containsMatchIn(userPreamble) ||
            FROM_IMPORT_PATTERN.containsMatchIn(userPreamble) ||
            DEF_PATTERN.containsMatchIn(userPreamble)
        ) {
            return ExecutionTier.COMPLEX
        }

        // Check code length for MEDIUM viability
        if (transformedCode.length > MEDIUM_MAX_LENGTH) {
            return ExecutionTier.COMPLEX
        }

        return ExecutionTier.MEDIUM
    }

    /**
     * Extracts user-authored code between the standard `import json` line
     * and the `def __karpfen_main__():` line. This is where user-defined imports
     * and helper function definitions appear. The standard boilerplate
     * (`import json`, `def __karpfen_main__`, result-printing block) is excluded.
     */
    private fun extractUserPreamble(transformedCode: String): String {
        val lines = transformedCode.lines()
        val defIndex = lines.indexOfFirst { it.trimStart().startsWith("def __karpfen_main__():") }
        if (defIndex == -1) return transformedCode

        // Collect lines between the standard `import json` and the main def.
        // Skip `import json` and blank lines at the top.
        val preambleLines = mutableListOf<String>()
        for (i in 0 until defIndex) {
            val trimmed = lines[i].trim()
            if (trimmed == "import json" || trimmed.isEmpty()) continue
            preambleLines.add(lines[i])
        }
        return preambleLines.joinToString("\n")
    }

    /**
     * Extracts the body of `def __karpfen_main__():` from the transformed code.
     * The body consists of all indented lines following the function definition,
     * up to the next unindented line (the result-printing block).
     *
     * @return The function body as a string, or null if the pattern is not found.
     */
    fun extractFunctionBody(transformedCode: String): String? {
        val lines = transformedCode.lines()
        val defIndex = lines.indexOfFirst { it.trimStart().startsWith("def __karpfen_main__():") }
        if (defIndex == -1) return null

        val bodyLines = mutableListOf<String>()
        for (i in (defIndex + 1) until lines.size) {
            val line = lines[i]
            // Stop at the result-printing block (unindented lines after the function body)
            if (line.isNotBlank() && !line.startsWith("\t") && !line.startsWith("    ")) {
                break
            }
            bodyLines.add(line)
        }

        return bodyLines.joinToString("\n").trimEnd()
    }

    /**
     * If the function body is a single `return <expr>` statement, extracts the expression.
     *
     * @return The expression string, or null if the body is not a simple return.
     */
    fun extractSimpleReturnExpression(body: String): String? {
        val trimmedLines = body.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (trimmedLines.size != 1) return null

        val line = trimmedLines[0]
        if (!line.startsWith("return ")) return null

        return line.removePrefix("return ").trim()
    }

    /**
     * Checks whether an expression consists only of simple tokens:
     * numeric literals, boolean literals, arithmetic/comparison operators,
     * boolean keywords, and parentheses.
     *
     * @return true if the expression can be evaluated by [SimpleExpressionEvaluator].
     */
    fun isSimpleExpression(expression: String): Boolean {
        if (expression.isBlank()) return false

        // Tokenize the expression and check that all characters are consumed
        var remaining = expression.trim()
        while (remaining.isNotEmpty()) {
            // Skip whitespace
            if (remaining[0].isWhitespace()) {
                remaining = remaining.trimStart()
                continue
            }

            // Try to match a valid token at the current position
            val token = matchNextToken(remaining) ?: return false
            remaining = remaining.substring(token.length)
        }

        return true
    }

    /**
     * Matches the next valid SIMPLE token at the start of the string.
     * Returns the matched token string, or null if no valid token is found.
     */
    private fun matchNextToken(s: String): String? {
        // Boolean keywords (must check before identifiers)
        for (keyword in listOf("True", "False", "and", "or", "not")) {
            if (s.startsWith(keyword)) {
                // Ensure the keyword is not a prefix of a longer identifier
                val afterKeyword = s.substring(keyword.length)
                if (afterKeyword.isEmpty() || !afterKeyword[0].isLetterOrDigit() && afterKeyword[0] != '_') {
                    return keyword
                }
            }
        }

        // Numeric literal (including scientific notation, negative handled as unary operator)
        val numMatch = Regex("""^\d+\.?\d*(?:[eE][+-]?\d+)?""").find(s)
        if (numMatch != null) {
            return numMatch.value
        }

        // Multi-character operators (must check before single-character)
        for (op in listOf("**", "//", "<=", ">=", "==", "!=")) {
            if (s.startsWith(op)) return op
        }

        // Single-character operators and parentheses
        if (s[0] in "+-*/%<>=()!") {
            return s[0].toString()
        }

        return null
    }
}
