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

import io.karpfen.io.karpfen.exec.SimpleExpressionEvaluator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SimpleExpressionEvaluatorTest {

    // ---- Basic arithmetic ----

    @Test
    fun `evaluate simple addition`() {
        assertEquals(5.0, SimpleExpressionEvaluator.evaluate("2 + 3"))
    }

    @Test
    fun `evaluate simple subtraction`() {
        assertEquals(7.0, SimpleExpressionEvaluator.evaluate("10 - 3"))
    }

    @Test
    fun `evaluate simple multiplication`() {
        assertEquals(12.0, SimpleExpressionEvaluator.evaluate("3 * 4"))
    }

    @Test
    fun `evaluate simple division`() {
        assertEquals(2.5, SimpleExpressionEvaluator.evaluate("5 / 2"))
    }

    @Test
    fun `evaluate modulo`() {
        assertEquals(1.0, SimpleExpressionEvaluator.evaluate("10 % 3"))
    }

    @Test
    fun `evaluate floor division`() {
        assertEquals(3.0, SimpleExpressionEvaluator.evaluate("7 // 2"))
    }

    @Test
    fun `evaluate power`() {
        assertEquals(8.0, SimpleExpressionEvaluator.evaluate("2 ** 3"))
    }

    @Test
    fun `evaluate power right associative`() {
        // 2 ** 3 ** 2 = 2 ** 9 = 512 (right-associative)
        assertEquals(512.0, SimpleExpressionEvaluator.evaluate("2 ** 3 ** 2"))
    }

    // ---- Operator precedence ----

    @Test
    fun `evaluate respects multiplication over addition precedence`() {
        assertEquals(14.0, SimpleExpressionEvaluator.evaluate("2 + 3 * 4"))
    }

    @Test
    fun `evaluate respects parentheses over precedence`() {
        assertEquals(20.0, SimpleExpressionEvaluator.evaluate("(2 + 3) * 4"))
    }

    @Test
    fun `evaluate nested parentheses`() {
        assertEquals(36.0, SimpleExpressionEvaluator.evaluate("((2 + 3) * (4 + 2)) + 6"))
    }

    @Test
    fun `evaluate chain of operations`() {
        assertEquals(12.0, SimpleExpressionEvaluator.evaluate("2 + 3 * 4 - 6 / 2 + 1"))
    }

    // ---- Unary operators ----

    @Test
    fun `evaluate negative number`() {
        assertEquals(-5.0, SimpleExpressionEvaluator.evaluate("-5"))
    }

    @Test
    fun `evaluate negative in expression`() {
        assertEquals(-2.0, SimpleExpressionEvaluator.evaluate("-5 + 3"))
    }

    @Test
    fun `evaluate negated parenthesized expression`() {
        assertEquals(-5.0, SimpleExpressionEvaluator.evaluate("-(2 + 3)"))
    }

    @Test
    fun `evaluate unary plus`() {
        assertEquals(5.0, SimpleExpressionEvaluator.evaluate("+5"))
    }

    @Test
    fun `evaluate double negative`() {
        assertEquals(5.0, SimpleExpressionEvaluator.evaluate("--5"))
    }

    // ---- Decimal and scientific notation ----

    @Test
    fun `evaluate decimal numbers`() {
        assertEquals(5.5, SimpleExpressionEvaluator.evaluate("2.5 + 3.0"))
    }

    @Test
    fun `evaluate scientific notation`() {
        assertEquals(1000.0, SimpleExpressionEvaluator.evaluate("1e3"))
    }

    @Test
    fun `evaluate scientific notation with decimal`() {
        assertEquals(1000.25, SimpleExpressionEvaluator.evaluate("1e3 + 2.5e-1"))
    }

    @Test
    fun `evaluate scientific notation uppercase E`() {
        assertEquals(100.0, SimpleExpressionEvaluator.evaluate("1E2"))
    }

    // ---- Boolean literals ----

    @Test
    fun `evaluate True`() {
        assertEquals(true, SimpleExpressionEvaluator.evaluate("True"))
    }

    @Test
    fun `evaluate False`() {
        assertEquals(false, SimpleExpressionEvaluator.evaluate("False"))
    }

    // ---- Comparison operators ----

    @Test
    fun `evaluate less than true`() {
        assertEquals(true, SimpleExpressionEvaluator.evaluate("3 < 5"))
    }

    @Test
    fun `evaluate less than false`() {
        assertEquals(false, SimpleExpressionEvaluator.evaluate("5 < 3"))
    }

    @Test
    fun `evaluate greater than`() {
        assertEquals(true, SimpleExpressionEvaluator.evaluate("5 > 3"))
    }

    @Test
    fun `evaluate less than or equal`() {
        assertEquals(true, SimpleExpressionEvaluator.evaluate("5 <= 5"))
    }

    @Test
    fun `evaluate greater than or equal true`() {
        assertEquals(true, SimpleExpressionEvaluator.evaluate("5 >= 5"))
    }

    @Test
    fun `evaluate greater than or equal false`() {
        assertEquals(false, SimpleExpressionEvaluator.evaluate("4 >= 5"))
    }

    @Test
    fun `evaluate equal true`() {
        assertEquals(true, SimpleExpressionEvaluator.evaluate("5 == 5"))
    }

    @Test
    fun `evaluate equal false`() {
        assertEquals(false, SimpleExpressionEvaluator.evaluate("3 == 4"))
    }

    @Test
    fun `evaluate not equal true`() {
        assertEquals(true, SimpleExpressionEvaluator.evaluate("3 != 4"))
    }

    @Test
    fun `evaluate not equal false`() {
        assertEquals(false, SimpleExpressionEvaluator.evaluate("5 != 5"))
    }

    // ---- Boolean operators ----

    @Test
    fun `evaluate and true`() {
        assertEquals(true, SimpleExpressionEvaluator.evaluate("True and True"))
    }

    @Test
    fun `evaluate and false`() {
        assertEquals(false, SimpleExpressionEvaluator.evaluate("True and False"))
    }

    @Test
    fun `evaluate or true`() {
        assertEquals(true, SimpleExpressionEvaluator.evaluate("True or False"))
    }

    @Test
    fun `evaluate or false`() {
        assertEquals(false, SimpleExpressionEvaluator.evaluate("False or False"))
    }

    @Test
    fun `evaluate not true`() {
        assertEquals(false, SimpleExpressionEvaluator.evaluate("not True"))
    }

    @Test
    fun `evaluate not false`() {
        assertEquals(true, SimpleExpressionEvaluator.evaluate("not False"))
    }

    @Test
    fun `evaluate complex boolean`() {
        // True and (False or True) = True and True = True
        assertEquals(true, SimpleExpressionEvaluator.evaluate("True and (False or True)"))
    }

    @Test
    fun `evaluate not with comparison`() {
        assertEquals(true, SimpleExpressionEvaluator.evaluate("not 5 < 3"))
    }

    // ---- Real robot demo expressions ----

    @Test
    fun `evaluate robot drive x update - no movement`() {
        // $(boundingBox->position->x) + 0.3 * $(direction->x)
        // Resolved: 5.0 + 0.3 * 0.0 = 5.0
        assertEquals(5.0, SimpleExpressionEvaluator.evaluate("5.0 + 0.3 * 0.0"))
    }

    @Test
    fun `evaluate robot drive y update - with movement`() {
        // $(boundingBox->position->y) + 0.3 * $(direction->y)
        // Resolved: 5.0 + 0.3 * 1.0 = 5.3
        assertEquals(5.3, SimpleExpressionEvaluator.evaluate("5.0 + 0.3 * 1.0") as Double, 0.001)
    }

    @Test
    fun `evaluate robot drive slow update`() {
        // 5.0 + 0.1 * 1.0 = 5.1
        assertEquals(5.1, SimpleExpressionEvaluator.evaluate("5.0 + 0.1 * 1.0") as Double, 0.001)
    }

    @Test
    fun `evaluate transition condition - obstacle closer`() {
        // $(d_closest_obstacle) < $(d_closest_wall) and $(d_closest_obstacle) < 0.25
        // With d_closest_obstacle=100.0, d_closest_wall=100.0
        assertEquals(false, SimpleExpressionEvaluator.evaluate("100.0 < 100.0 and 100.0 < 0.25"))
    }

    @Test
    fun `evaluate transition condition - wall closer`() {
        // $(d_closest_wall) < $(d_closest_obstacle) and $(d_closest_wall) < 0.25
        assertEquals(false, SimpleExpressionEvaluator.evaluate("100.0 < 100.0 and 100.0 < 0.25"))
    }

    @Test
    fun `evaluate transition condition - drive slow`() {
        // $(d_closest_obstacle) < 1.0 or $(d_closest_wall) < 1.0
        assertEquals(false, SimpleExpressionEvaluator.evaluate("100.0 < 1.0 or 100.0 < 1.0"))
    }

    @Test
    fun `evaluate transition condition - drive fast`() {
        // $(d_closest_obstacle) >= 1.0 and $(d_closest_wall) >= 1.0
        assertEquals(true, SimpleExpressionEvaluator.evaluate("100.0 >= 1.0 and 100.0 >= 1.0"))
    }

    @Test
    fun `evaluate transition with close obstacle`() {
        // Robot is near an obstacle (0.2 < 0.5 and 0.2 < 0.25 = True and True = True)
        assertEquals(true, SimpleExpressionEvaluator.evaluate("0.2 < 0.5 and 0.2 < 0.25"))
    }

    @Test
    fun `evaluate transition or condition with one true`() {
        // 0.5 < 1.0 or 2.0 < 1.0 = True or False = True
        assertEquals(true, SimpleExpressionEvaluator.evaluate("0.5 < 1.0 or 2.0 < 1.0"))
    }

    // ---- Edge cases ----

    @Test
    fun `evaluate single number`() {
        assertEquals(42.0, SimpleExpressionEvaluator.evaluate("42"))
    }

    @Test
    fun `evaluate single decimal`() {
        assertEquals(3.14, SimpleExpressionEvaluator.evaluate("3.14") as Double, 0.001)
    }

    @Test
    fun `evaluate zero`() {
        assertEquals(0.0, SimpleExpressionEvaluator.evaluate("0"))
    }

    @Test
    fun `evaluate whitespace variations`() {
        assertEquals(5.0, SimpleExpressionEvaluator.evaluate("  2  +  3  "))
    }

    @Test
    fun `evaluate division by zero returns infinity`() {
        assertEquals(Double.POSITIVE_INFINITY, SimpleExpressionEvaluator.evaluate("1 / 0"))
    }

    @Test
    fun `evaluate complex nested expression`() {
        // ((3 + 4) * 2 - 1) / (5 - 2) = (14 - 1) / 3 = 13 / 3 ≈ 4.333
        assertEquals(4.333, SimpleExpressionEvaluator.evaluate("((3 + 4) * 2 - 1) / (5 - 2)") as Double, 0.01)
    }

    // ---- Error cases ----

    @Test
    fun `evaluate throws on invalid expression`() {
        assertThrows<IllegalArgumentException> {
            SimpleExpressionEvaluator.evaluate("hello world")
        }
    }

    @Test
    fun `evaluate throws on empty expression`() {
        assertThrows<IllegalArgumentException> {
            SimpleExpressionEvaluator.evaluate("")
        }
    }

    @Test
    fun `evaluate throws on unmatched parenthesis`() {
        assertThrows<IllegalArgumentException> {
            SimpleExpressionEvaluator.evaluate("(2 + 3")
        }
    }

    @Test
    fun `evaluate throws on extra closing parenthesis`() {
        assertThrows<IllegalArgumentException> {
            SimpleExpressionEvaluator.evaluate("2 + 3)")
        }
    }
}
