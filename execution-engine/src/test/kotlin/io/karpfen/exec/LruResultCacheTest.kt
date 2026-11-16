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

import io.karpfen.io.karpfen.exec.LruResultCache
import io.karpfen.io.karpfen.exec.ResultCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The bounds and the order of [LruResultCache], and [ResultCache.NONE]. No Python runs here.
 */
class LruResultCacheTest {

    @Test
    fun `a stored result comes back, also no result, and an unknown text has none`() {
        val cache = LruResultCache(10, 1_000_000)
        cache.store("a", "1")
        cache.store("b", null)

        assertEquals("1", cache.lookup("a")?.result)
        val none = cache.lookup("b")
        assertTrue(none != null && none.result == null)
        assertNull(cache.lookup("c"))
    }

    @Test
    fun `the least recently used entry leaves first when the entries are full`() {
        val cache = LruResultCache(2, 1_000_000)
        cache.store("a", "1")
        cache.store("b", "2")
        cache.lookup("a")
        cache.store("c", "3")

        assertEquals(2, cache.size)
        assertNull(cache.lookup("b"))
        assertEquals("1", cache.lookup("a")?.result)
        assertEquals("3", cache.lookup("c")?.result)
    }

    @Test
    fun `entries leave when their memory passes the bound, and an entry too large alone is not stored`() {
        val entry = 96L + 2 * 2
        val cache = LruResultCache(100, 2 * entry)
        cache.store("a", "1")
        cache.store("b", "2")
        cache.store("c", "3")

        assertEquals(2, cache.size)
        assertEquals(2 * entry, cache.memoryBytes)
        assertNull(cache.lookup("a"))

        cache.store("x".repeat(200), "4")
        assertNull(cache.lookup("x".repeat(200)))
        assertEquals(2, cache.size)
    }

    @Test
    fun `storing a text again replaces its result and counts its memory once`() {
        val cache = LruResultCache(10, 1_000_000)
        cache.store("a", "1")
        cache.store("a", "22")

        assertEquals("22", cache.lookup("a")?.result)
        assertEquals(1, cache.size)
        assertEquals(96L + 2 * 3, cache.memoryBytes)
    }

    @Test
    fun `bounds below one are refused`() {
        assertThrows(IllegalArgumentException::class.java) { LruResultCache(0, 10) }
        assertThrows(IllegalArgumentException::class.java) { LruResultCache(10, 0) }
    }

    @Test
    fun `threads that store and look up at the same time keep the bounds`() {
        val cache = LruResultCache(50, 1_000_000)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        repeat(4) { t ->
            pool.submit {
                start.await()
                repeat(2000) { i ->
                    cache.store("t$t-$i", "$i")
                    cache.lookup("t$t-${i / 2}")
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        assertEquals(50, cache.size)
    }

    @Test
    fun `the cache of an environment without caching stores nothing`() {
        ResultCache.NONE.store("a", "1")

        assertNull(ResultCache.NONE.lookup("a"))
        assertFalse(ResultCache.NONE.enabled)
    }
}
