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
package io.karpfen.observation

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the dependency direction of model observation: the server and the environment registry use the observation
 * package, so it imports neither of them and the feature forms no package cycle.
 */
class ObservationDependencyTest {

    @Test
    fun `the observation package imports neither the server nor the environment registry`() {
        val sources = File(OBSERVATION_SOURCES).walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "No sources found in $OBSERVATION_SOURCES")

        val reversed = sources.flatMap { source ->
            source.readLines()
                .map { it.trim() }
                .filter { line -> line.startsWith("import ") && USERS.any { line.startsWith("import $it") } }
                .map { "${source.name}: $it" }
        }

        assertEquals(emptyList(), reversed)
    }

    private companion object {
        /** The production sources of the observation package, relative to the runtime directory. */
        const val OBSERVATION_SOURCES = "src/main/kotlin/io/karpfen/observation"

        /** The packages that use the observation package. */
        val USERS = listOf("io.karpfen.server.", "io.karpfen.env.")
    }
}
