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
package io.karpfen.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * The keys of the result cache in `application.conf`: their defaults, the values of the file of the repository, and
 * values that are no positive number.
 */
class ApplicationConfigTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `without the keys caching is on with 10000 results and 64 megabytes`() {
        val engine = load("engine {\n  defaultTickDelayMs = 5\n}").engine

        assertEquals(true, engine.resultCaching)
        assertEquals(10_000, engine.resultCacheMaxEntries)
        assertEquals(64L, engine.resultCacheMaxMegabytes)
    }

    @Test
    fun `the keys set the switch and the bounds, also between other keys of the engine`() {
        val engine = load(
            """
            engine {
              defaultTickDelayMs = 5
              resultCaching           = false
              resultCacheMaxEntries   = 20
              resultCacheMaxMegabytes = 3
              eventConsumptionOnFire = true
            }
            """.trimIndent()
        ).engine

        assertEquals(false, engine.resultCaching)
        assertEquals(20, engine.resultCacheMaxEntries)
        assertEquals(3L, engine.resultCacheMaxMegabytes)
        assertEquals(5, engine.defaultTickDelayMs)
    }

    @Test
    fun `bounds that are no positive number fall back to the defaults`() {
        val engine = load("engine {\n  resultCacheMaxEntries = 0\n  resultCacheMaxMegabytes = many\n}").engine

        assertEquals(10_000, engine.resultCacheMaxEntries)
        assertEquals(64L, engine.resultCacheMaxMegabytes)
    }

    @Test
    fun `the file of the repository switches caching on`() {
        val engine = ApplicationConfig.loadFromFile("application.conf").engine

        assertEquals(true, engine.resultCaching)
        assertEquals(10_000, engine.resultCacheMaxEntries)
        assertEquals(64L, engine.resultCacheMaxMegabytes)
    }

    private fun load(text: String): ApplicationConfig {
        val file = File(folder, "application.conf")
        file.writeText(text)
        return ApplicationConfig.loadFromFile(file.path)
    }
}
