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
 * A Kotlin-native evaluator for simple arithmetic and boolean expressions.
 *
 * Supports:
 * - Numeric literals (integer, decimal, scientific notation)
 * - Boolean literals (`True`, `False` — Python convention)
 * - Arithmetic operators: `+`, `-`, `*`, `/`, `%`, `//` (floor division), `**` (power)
 * - Comparison operators: `<`, `>`, `<=`, `>=`, `==`, `!=`
 * - Boolean operators: `and`, `or`, `not`
 * - Parentheses for grouping
 * - Unary `+` and `-`
 *
 * Operator precedence (low to high):
 * 1. `or`
 * 2. `and`
 * 3. `not`
 * 4. `<`, `>`, `<=`, `>=`, `==`, `!=`
 * 5. `+`, `-`
 * 6. `*`, `/`, `%`, `//`
 * 7. Unary `+`, `-`
 * 8. `**` (right-associative; its right operand may carry a unary operator)
 * 9. Atom (number, boolean, parenthesized expression)
 *
 * All numeric values are represented as [Double] internally, consistent with how
 * Python results are parsed in [MacroProcessor.parseResult].
 *
 * The evaluator stands in for Python, so it follows Python where a result could differ: `and` and `or` return one of
 * their operands, `%` and `//` round towards negative infinity, and `**` binds tighter than a unary operator on its
 * left. Where Python raises or leaves the floats, the evaluator gives no result and throws [NotSimpleException]
 * instead: a division, floor division or modulo by zero, zero to a negative power, and a negative number to a
 * fractional power. The caller then runs the code in Python, which gives its own result or error.
 */
object SimpleExpressionEvaluator {

    /**
     * The expression is valid but its result is not one the evaluator can give as Python would; the code must run in
     * Python. [message] names the case.
     */
    class NotSimpleException(message: String) : RuntimeException(message)

    /**
     * Evaluates the given expression string and returns the result.
     *
     * @param expression A simple arithmetic or boolean expression (Python-like syntax).
     * @return [Double] for numeric results, [Boolean] for boolean results.
     * @throws IllegalArgumentException if the expression cannot be parsed.
     * @throws NotSimpleException if Python would raise or give no float; see the class description.
     */
    fun evaluate(expression: String): Any {
        val tokens = tokenize(expression)
        val parser = Parser(tokens)
        val result = parser.parseExpression()
        if (parser.position < tokens.size) {
            throw IllegalArgumentException(
                "Unexpected token '${tokens[parser.position]}' at position ${parser.position} in expression: $expression"
            )
        }
        return result
    }

    // ---- Token types ----

    private sealed class Token {
        data class Number(val value: Double) : Token() {
            override fun toString() = value.toString()
        }
        data class BooleanLiteral(val value: Boolean) : Token() {
            override fun toString() = if (value) "True" else "False"
        }
        data class Operator(val op: String) : Token() {
            override fun toString() = op
        }
        data class Keyword(val word: String) : Token() {
            override fun toString() = word
        }
        object LParen : Token() {
            override fun toString() = "("
        }
        object RParen : Token() {
            override fun toString() = ")"
        }
    }

    // ---- Tokenizer ----

    private fun tokenize(expression: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val s = expression.trim()

        while (i < s.length) {
            // Skip whitespace
            if (s[i].isWhitespace()) {
                i++
                continue
            }

            // Check for keywords: True, False, and, or, not
            val keyword = tryMatchKeyword(s, i)
            if (keyword != null) {
                when (keyword) {
                    "True" -> tokens.add(Token.BooleanLiteral(true))
                    "False" -> tokens.add(Token.BooleanLiteral(false))
                    else -> tokens.add(Token.Keyword(keyword))
                }
                i += keyword.length
                continue
            }

            // Check for numeric literal
            if (s[i].isDigit() || (s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit())) {
                val numStr = matchNumber(s, i)
                tokens.add(Token.Number(numStr.toDouble()))
                i += numStr.length
                continue
            }

            // Check for multi-character operators
            if (i + 1 < s.length) {
                val twoChar = s.substring(i, i + 2)
                if (twoChar in listOf("**", "//", "<=", ">=", "==", "!=")) {
                    tokens.add(Token.Operator(twoChar))
                    i += 2
                    continue
                }
            }

            // Single-character tokens
            when (s[i]) {
                '(' -> { tokens.add(Token.LParen); i++ }
                ')' -> { tokens.add(Token.RParen); i++ }
                '+', '-', '*', '/', '%', '<', '>', '!' -> {
                    tokens.add(Token.Operator(s[i].toString()))
                    i++
                }
                else -> throw IllegalArgumentException(
                    "Unexpected character '${s[i]}' at position $i in expression: $expression"
                )
            }
        }

        return tokens
    }

    /**
     * Tries to match a keyword (True, False, and, or, not) at position [start].
     * Returns the keyword string if matched and followed by a non-alphanumeric character
     * (or end of string), null otherwise.
     */
    private fun tryMatchKeyword(s: String, start: Int): String? {
        for (kw in listOf("True", "False", "and", "or", "not")) {
            if (s.startsWith(kw, start)) {
                val end = start + kw.length
                if (end >= s.length || (!s[end].isLetterOrDigit() && s[end] != '_')) {
                    return kw
                }
            }
        }
        return null
    }

    /**
     * Matches a numeric literal starting at position [start].
     * Handles integers, decimals, and scientific notation (e.g., 1e3, 2.5E-1).
     */
    private fun matchNumber(s: String, start: Int): String {
        var i = start

        // Integer part
        while (i < s.length && s[i].isDigit()) i++

        // Decimal part
        if (i < s.length && s[i] == '.' && (i + 1 >= s.length || s[i + 1].isDigit())) {
            i++ // skip '.'
            while (i < s.length && s[i].isDigit()) i++
        }

        // Exponent part
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++ // skip 'e'/'E'
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++ // skip sign
            while (i < s.length && s[i].isDigit()) i++
        }

        return s.substring(start, i)
    }

    // ---- Recursive Descent Parser/Evaluator ----

    private class Parser(private val tokens: List<Token>) {
        var position = 0

        private fun peek(): Token? = if (position < tokens.size) tokens[position] else null
        private fun advance(): Token = tokens[position++]

        /**
         * Top-level expression: orExpr
         */
        fun parseExpression(): Any = parseOr()

        /**
         * orExpr → andExpr ("or" andExpr)*
         */
        private fun parseOr(): Any {
            var left = parseAnd()
            while (peek() is Token.Keyword && (peek() as Token.Keyword).word == "or") {
                advance() // consume "or"
                val right = parseAnd()
                left = if (toBool(left)) left else right
            }
            return left
        }

        /**
         * andExpr → notExpr ("and" notExpr)*
         */
        private fun parseAnd(): Any {
            var left = parseNot()
            while (peek() is Token.Keyword && (peek() as Token.Keyword).word == "and") {
                advance() // consume "and"
                val right = parseNot()
                left = if (toBool(left)) right else left
            }
            return left
        }

        /**
         * notExpr → "not" notExpr | comparison
         */
        private fun parseNot(): Any {
            if (peek() is Token.Keyword && (peek() as Token.Keyword).word == "not") {
                advance() // consume "not"
                val operand = parseNot()
                return !toBool(operand)
            }
            return parseComparison()
        }

        /**
         * comparison → addition (("<" | ">" | "<=" | ">=" | "==" | "!=") addition)?
         */
        private fun parseComparison(): Any {
            var left = parseAddition()
            val peeked = peek()
            if (peeked is Token.Operator && peeked.op in listOf("<", ">", "<=", ">=", "==", "!=")) {
                val op = (advance() as Token.Operator).op
                val right = parseAddition()
                val l = toDouble(left)
                val r = toDouble(right)
                left = when (op) {
                    "<" -> l < r
                    ">" -> l > r
                    "<=" -> l <= r
                    ">=" -> l >= r
                    "==" -> l == r
                    "!=" -> l != r
                    else -> throw IllegalStateException("Unknown comparison operator: $op")
                }
            }
            return left
        }

        /**
         * addition → multiplication (("+" | "-") multiplication)*
         */
        private fun parseAddition(): Any {
            var left = parseMultiplication()
            while (true) {
                val peeked = peek()
                if (peeked is Token.Operator && peeked.op in listOf("+", "-")) {
                    val op = (advance() as Token.Operator).op
                    val right = parseMultiplication()
                    left = when (op) {
                        "+" -> toDouble(left) + toDouble(right)
                        "-" -> toDouble(left) - toDouble(right)
                        else -> throw IllegalStateException()
                    }
                } else break
            }
            return left
        }

        /**
         * multiplication → unary (("*" | "/" | "%" | "//") unary)*
         */
        private fun parseMultiplication(): Any {
            var left = parseUnary()
            while (true) {
                val peeked = peek()
                if (peeked is Token.Operator && peeked.op in listOf("*", "/", "%", "//")) {
                    val op = (advance() as Token.Operator).op
                    val right = parseUnary()
                    left = when (op) {
                        "*" -> toDouble(left) * toDouble(right)
                        "/" -> toDouble(left) / nonZero(toDouble(right))
                        "%" -> pythonModulo(toDouble(left), nonZero(toDouble(right)))
                        "//" -> pythonFloorDivision(toDouble(left), nonZero(toDouble(right)))
                        else -> throw IllegalStateException()
                    }
                } else break
            }
            return left
        }

        /**
         * power → atom ("**" unary)?   (right-associative, and binding tighter than a unary operator on its left, as
         * in Python: `-2 ** 2` is -4 and `2 ** -1` is 0.5)
         */
        private fun parsePower(): Any {
            var base = parseAtom()
            val peeked = peek()
            if (peeked is Token.Operator && peeked.op == "**") {
                advance() // consume "**"
                val exponent = parseUnary() // right-associative recursion
                base = pythonPower(toDouble(base), toDouble(exponent))
            }
            return base
        }

        /**
         * unary → ("-" | "+") unary | power
         */
        private fun parseUnary(): Any {
            val peeked = peek()
            if (peeked is Token.Operator && peeked.op in listOf("+", "-")) {
                val op = (advance() as Token.Operator).op
                val operand = parseUnary()
                return when (op) {
                    "-" -> -toDouble(operand)
                    "+" -> toDouble(operand)
                    else -> throw IllegalStateException()
                }
            }
            return parsePower()
        }

        /**
         * atom → NUMBER | BOOLEAN | "(" expression ")"
         */
        private fun parseAtom(): Any {
            val token = peek() ?: throw IllegalArgumentException(
                "Unexpected end of expression"
            )

            return when (token) {
                is Token.Number -> {
                    advance()
                    token.value
                }
                is Token.BooleanLiteral -> {
                    advance()
                    token.value
                }
                is Token.LParen -> {
                    advance() // consume '('
                    val result = parseExpression()
                    val closing = peek()
                    if (closing !is Token.RParen) {
                        throw IllegalArgumentException(
                            "Expected ')' but got ${closing ?: "end of expression"}"
                        )
                    }
                    advance() // consume ')'
                    result
                }
                else -> throw IllegalArgumentException(
                    "Unexpected token: $token"
                )
            }
        }

        /** Returns [divisor], or refuses a division by zero, which raises `ZeroDivisionError` in Python. */
        private fun nonZero(divisor: Double): Double {
            if (divisor == 0.0) throw NotSimpleException("division by zero")
            return divisor
        }

        /** Python's `%` on floats: the result has the sign of [divisor], as in `float.__mod__`. */
        private fun pythonModulo(dividend: Double, divisor: Double): Double {
            var mod = dividend % divisor
            if (mod != 0.0 && ((divisor < 0) != (mod < 0))) mod += divisor
            return if (mod == 0.0) Math.copySign(0.0, divisor) else mod
        }

        /** Python's `//` on floats, computed as `float.__floordiv__` does it from the remainder. */
        private fun pythonFloorDivision(dividend: Double, divisor: Double): Double {
            val mod = dividend % divisor
            var div = (dividend - mod) / divisor
            if (mod != 0.0 && ((divisor < 0) != (mod < 0))) div -= 1.0
            if (div == 0.0) return Math.copySign(0.0, dividend / divisor)
            val floor = kotlin.math.floor(div)
            return if (div - floor > 0.5) floor + 1.0 else floor
        }

        /**
         * Python's `**` where it gives a float: refuses `0 ** negative`, which raises `ZeroDivisionError`, and a
         * negative base with a fractional exponent, which gives a complex number in Python.
         */
        private fun pythonPower(base: Double, exponent: Double): Double {
            if (base == 0.0 && exponent < 0) throw NotSimpleException("zero to a negative power")
            if (base < 0 && exponent != kotlin.math.floor(exponent)) throw NotSimpleException("complex result")
            return Math.pow(base, exponent)
        }

        private fun toDouble(value: Any): Double = when (value) {
            is Double -> value
            is Boolean -> if (value) 1.0 else 0.0
            is Number -> value.toDouble()
            else -> throw IllegalArgumentException("Cannot convert $value to number")
        }

        private fun toBool(value: Any): Boolean = when (value) {
            is Boolean -> value
            is Double -> value != 0.0
            is Number -> value.toDouble() != 0.0
            else -> throw IllegalArgumentException("Cannot convert $value to boolean")
        }
    }
}
