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

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import dsl.textual.KstatesDSLConverter
import io.karpfen.Engine
import io.karpfen.io.karpfen.messages.EventBus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * End-to-end test of parallel states: a SPLIT fans into two branches that advance independently, and a
 * JOIN collapses them back into a single state. Uses literal actions only (no Python).
 */
class ParallelStatesEngineTest {

    private val kmeta = """
        type "Thing" "a thing with a log" {
            prop("log", list("string"))
        }
    """.trimIndent()

    private val kmodel = """
        make object "t":"Thing" { }
    """.trimIndent()

    // init --SPLIT--> {left1, right1}; left1->left2, right1->right2; {left2,right2} --JOIN--> merged
    private val kstates = """
        STATEMACHINE ATTACHED TO "Thing" {
            STATES {
                INITIAL STATE "init" { }
                STATE "left1" { ENTRY { APPEND("log", "L1") } }
                STATE "left2" { }
                STATE "right1" { ENTRY { APPEND("log", "R1") } }
                STATE "right2" { }
                STATE "merged" { ENTRY { APPEND("log", "M") } }
            }
            TRANSITIONS {
                SPLIT "init" -> "left1", "right1" { }
                TRANSITION "left1" -> "left2" { }
                TRANSITION "right1" -> "right2" { }
                JOIN "left2", "right2" -> "merged" { }
            }
        }
    """.trimIndent()

    @Test
    fun `split runs both branches and join merges back to a simple state`() {
        val metamodel = KmetaDSLConverter.parseKmetaString(kmeta)
        val model = KmodelDSLConverter.parseKmodelString(kmodel, metamodel)
        val sm = KstatesDSLConverter.parseKstatesString(kstates)

        val engine = Engine(metamodel, model, mapOf("t" to sm), tickDelayMS = 10, eventBus = EventBus())
        engine.start()
        Thread.sleep(600)
        engine.stop()

        val log = model.objects.first().getProp("log").map { it.toString() }
        assertTrue(log.contains("L1"), "left branch entry should have run independently; log=$log")
        assertTrue(log.contains("R1"), "right branch entry should have run independently; log=$log")
        assertTrue(log.contains("M"), "merged entry should have run after the join collapsed the branches; log=$log")
    }
}
