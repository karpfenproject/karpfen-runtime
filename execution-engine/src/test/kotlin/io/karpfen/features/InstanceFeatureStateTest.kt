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
package io.karpfen.features

import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.io.karpfen.features.language.HistoryFeature
import io.karpfen.io.karpfen.features.runtime.BreakpointFeature
import io.karpfen.io.karpfen.features.runtime.TickByTickFeature
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The state that features keep per state machine instance is forgotten when the instance leaves, so an instance that
 * later runs on the same object starts without it, while the state of other objects stays.
 */
class InstanceFeatureStateTest {

    @Test
    fun `forgetting an instance drops its histories and keeps those of other objects`() {
        val machine = KstatesReader().read(
            """
            STATEMACHINE "remembering" ATTACHABLE TO "Bulb" {
                STATES {
                    INITIAL SHALLOW HISTORY STATE "outer" {
                        STATE "first" { }
                        STATE "second" { }
                    }
                }
                TRANSITIONS { }
            }
            """.trimIndent()
        ).model!!
        val outer = machine.states.single()
        val second = outer.innerStates[1]
        val history = HistoryFeature()
        history.updateHistory("b1", listOf(outer, second))
        history.updateHistory("b2", listOf(outer, second))

        history.forget("b1")

        assertNull(history.createTransitionOrNull("b1", outer))
        assertEquals("second", assertNotNull(history.createTransitionOrNull("b2", outer)).toState)
    }

    @Test
    fun `forgetting an instance drops its breakpoint overrides and keeps those of other objects`() {
        val breakpoints = BreakpointFeature(TickByTickFeature())
        for (objectId in listOf("b1", "b2")) {
            breakpoints.onMessage(
                """{"action": "addStateOverride", "modelElementId": "$objectId", "stateIdentifier": "dark", "timing": "entry", "breakpointStatus": true}"""
            )
        }

        breakpoints.forget("b1")

        val overrides = breakpoints.onMessage("""{"action": "getOverrides"}""")
        assertTrue(overrides.contains("b2={dark=true}"), overrides)
        assertTrue(!overrides.contains("b1"), overrides)
    }
}
