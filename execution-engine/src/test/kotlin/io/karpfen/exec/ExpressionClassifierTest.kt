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

import io.karpfen.io.karpfen.exec.ExecutionTier
import io.karpfen.io.karpfen.exec.ExpressionClassifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExpressionClassifierTest {

    /**
     * Helper to build a mock transformed Python script wrapping the given function body.
     */
    private fun wrapInPython(body: String, imports: String = "", functionDefs: String = ""): String {
        val sb = StringBuilder()
        sb.appendLine("import json")
        sb.appendLine()
        if (imports.isNotBlank()) {
            sb.appendLine(imports)
            sb.appendLine()
        }
        if (functionDefs.isNotBlank()) {
            sb.appendLine(functionDefs)
            sb.appendLine()
        }
        sb.appendLine("def __karpfen_main__():")
        for (line in body.lines()) {
            sb.appendLine("\t$line")
        }
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

    // ---- SIMPLE classification ----

    @Test
    fun `classifies simple numeric return as SIMPLE`() {
        val code = wrapInPython("return 5.0 + 0.3 * 0.0")
        assertEquals(ExecutionTier.SIMPLE, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies simple boolean comparison as SIMPLE`() {
        val code = wrapInPython("return 100.0 < 100.0 and 100.0 < 0.25")
        assertEquals(ExecutionTier.SIMPLE, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies boolean or expression as SIMPLE`() {
        val code = wrapInPython("return 100.0 < 1.0 or 100.0 < 1.0")
        assertEquals(ExecutionTier.SIMPLE, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies boolean with gte as SIMPLE`() {
        val code = wrapInPython("return 100.0 >= 1.0 and 100.0 >= 1.0")
        assertEquals(ExecutionTier.SIMPLE, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies single number return as SIMPLE`() {
        val code = wrapInPython("return 42")
        assertEquals(ExecutionTier.SIMPLE, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies arithmetic with power as SIMPLE`() {
        val code = wrapInPython("return (3.0 - 2.0) ** 2 + (5.0 - 3.0) ** 2")
        assertEquals(ExecutionTier.SIMPLE, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies True and False as SIMPLE`() {
        val code = wrapInPython("return True and False")
        assertEquals(ExecutionTier.SIMPLE, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies not expression as SIMPLE`() {
        val code = wrapInPython("return not True")
        assertEquals(ExecutionTier.SIMPLE, ExpressionClassifier.classify(code))
    }

    // ---- MEDIUM classification ----

    @Test
    fun `classifies multi-line code without imports as MEDIUM`() {
        val body = """
            x = 5 + 3
            return x * 2
        """.trimIndent()
        val code = wrapInPython(body)
        assertEquals(ExecutionTier.MEDIUM, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies code with conditionals as MEDIUM`() {
        val body = """
            if 5 > 3:
                return True
            else:
                return False
        """.trimIndent()
        val code = wrapInPython(body)
        assertEquals(ExecutionTier.MEDIUM, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies code with for loop as MEDIUM`() {
        val body = """
            total = 0
            for i in range(10):
                total = total + i
            return total
        """.trimIndent()
        val code = wrapInPython(body)
        assertEquals(ExecutionTier.MEDIUM, ExpressionClassifier.classify(code))
    }

    // ---- COMPLEX classification ----

    @Test
    fun `classifies code with import math as COMPLEX`() {
        val code = wrapInPython("return math.sqrt(9.0)", imports = "import math")
        assertEquals(ExecutionTier.COMPLEX, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies code with import numpy as COMPLEX`() {
        val code = wrapInPython(
            "q = np.array([1.0, 2.0])\nreturn np.linalg.norm(q)",
            imports = "import numpy as np"
        )
        assertEquals(ExecutionTier.COMPLEX, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies code with function definitions as COMPLEX`() {
        val funcDef = """
            def point_to_point_distance(p1, p2):
            	import math
            	return math.sqrt((p1["x"] - p2["x"]) ** 2 + (p1["y"] - p2["y"]) ** 2)
        """.trimIndent()
        val code = wrapInPython("return point_to_point_distance(a, b)", functionDefs = funcDef)
        assertEquals(ExecutionTier.COMPLEX, ExpressionClassifier.classify(code))
    }

    @Test
    fun `classifies code with from import as COMPLEX`() {
        val code = wrapInPython("return sqrt(9)", imports = "from math import sqrt")
        assertEquals(ExecutionTier.COMPLEX, ExpressionClassifier.classify(code))
    }

    // ---- extractFunctionBody ----

    @Test
    fun `extractFunctionBody returns body lines`() {
        val code = wrapInPython("return 5.0 + 3.0")
        val body = ExpressionClassifier.extractFunctionBody(code)
        assertNotNull(body)
        assertTrue(body!!.contains("return 5.0 + 3.0"))
    }

    @Test
    fun `extractFunctionBody returns null for non-standard code`() {
        val code = "print('hello world')"
        val body = ExpressionClassifier.extractFunctionBody(code)
        assertNull(body)
    }

    // ---- extractSimpleReturnExpression ----

    @Test
    fun `extractSimpleReturnExpression extracts expression`() {
        val expr = ExpressionClassifier.extractSimpleReturnExpression("\treturn 5.0 + 3.0")
        assertEquals("5.0 + 3.0", expr)
    }

    @Test
    fun `extractSimpleReturnExpression returns null for multi-line`() {
        val body = "\tx = 5\n\treturn x"
        val expr = ExpressionClassifier.extractSimpleReturnExpression(body)
        assertNull(expr)
    }

    // ---- isSimpleExpression ----

    @Test
    fun `isSimpleExpression accepts arithmetic`() {
        assertTrue(ExpressionClassifier.isSimpleExpression("5.0 + 3.0 * 2.0"))
    }

    @Test
    fun `isSimpleExpression accepts comparison with boolean`() {
        assertTrue(ExpressionClassifier.isSimpleExpression("100.0 < 100.0 and 100.0 < 0.25"))
    }

    @Test
    fun `isSimpleExpression rejects function calls`() {
        assertFalse(ExpressionClassifier.isSimpleExpression("math.sqrt(9)"))
    }

    @Test
    fun `isSimpleExpression rejects variable names`() {
        assertFalse(ExpressionClassifier.isSimpleExpression("x + y"))
    }

    @Test
    fun `isSimpleExpression rejects string literals`() {
        assertFalse(ExpressionClassifier.isSimpleExpression("\"hello\""))
    }

    @Test
    fun `isSimpleExpression accepts negative numbers`() {
        assertTrue(ExpressionClassifier.isSimpleExpression("-5.0 + 3.0"))
    }

    @Test
    fun `isSimpleExpression accepts boolean literals`() {
        assertTrue(ExpressionClassifier.isSimpleExpression("True and False"))
    }

    @Test
    fun `isSimpleExpression accepts not`() {
        assertTrue(ExpressionClassifier.isSimpleExpression("not True"))
    }

    @Test
    fun `isSimpleExpression accepts complex robot expression`() {
        assertTrue(ExpressionClassifier.isSimpleExpression("100.0 >= 1.0 and 100.0 >= 1.0"))
    }

    @Test
    fun `isSimpleExpression accepts power operator`() {
        assertTrue(ExpressionClassifier.isSimpleExpression("2 ** 3"))
    }

    @Test
    fun `isSimpleExpression accepts floor division`() {
        assertTrue(ExpressionClassifier.isSimpleExpression("7 // 2"))
    }

    @Test
    fun `isSimpleExpression rejects list literal`() {
        assertFalse(ExpressionClassifier.isSimpleExpression("[1, 2, 3]"))
    }

    @Test
    fun `isSimpleExpression rejects dict literal`() {
        assertFalse(ExpressionClassifier.isSimpleExpression("{\"x\": 1}"))
    }

    @Test
    fun `isSimpleExpression accepts scientific notation`() {
        assertTrue(ExpressionClassifier.isSimpleExpression("1e3 + 2.5e-1"))
    }
}
