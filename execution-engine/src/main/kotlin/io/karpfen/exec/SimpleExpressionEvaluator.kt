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
 * 7. `**` (right-associative)
 * 8. Unary `+`, `-`
 * 9. Atom (number, boolean, parenthesized expression)
 *
 * All numeric values are represented as [Double] internally, consistent with how
 * Python results are parsed in [MacroProcessor.parseResult].
 */
object SimpleExpressionEvaluator {

    /**
     * Evaluates the given expression string and returns the result.
     *
     * @param expression A simple arithmetic or boolean expression (Python-like syntax).
     * @return [Double] for numeric results, [Boolean] for boolean results.
     * @throws IllegalArgumentException if the expression cannot be parsed.
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
                left = toBool(left) || toBool(right)
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
                left = toBool(left) && toBool(right)
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
         * multiplication → power (("*" | "/" | "%" | "//") power)*
         */
        private fun parseMultiplication(): Any {
            var left = parsePower()
            while (true) {
                val peeked = peek()
                if (peeked is Token.Operator && peeked.op in listOf("*", "/", "%", "//")) {
                    val op = (advance() as Token.Operator).op
                    val right = parsePower()
                    left = when (op) {
                        "*" -> toDouble(left) * toDouble(right)
                        "/" -> toDouble(left) / toDouble(right)
                        "%" -> toDouble(left) % toDouble(right)
                        "//" -> kotlin.math.floor(toDouble(left) / toDouble(right))
                        else -> throw IllegalStateException()
                    }
                } else break
            }
            return left
        }

        /**
         * power → unary ("**" power)?   (right-associative)
         */
        private fun parsePower(): Any {
            var base = parseUnary()
            val peeked = peek()
            if (peeked is Token.Operator && peeked.op == "**") {
                advance() // consume "**"
                val exponent = parsePower() // right-associative recursion
                base = Math.pow(toDouble(base), toDouble(exponent))
            }
            return base
        }

        /**
         * unary → ("-" | "+") unary | atom
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
            return parseAtom()
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
