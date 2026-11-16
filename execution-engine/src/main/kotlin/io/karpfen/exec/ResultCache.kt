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
 * Remembers what Python gave for a finished code text, to answer the same text again without Python.
 *
 * The key is the complete code text after the runtime has written the values of the paths into it, so two calls share
 * an entry only when they would hand Python exactly the same text. The value is the raw result of the call, the text
 * Python gave or null for code without a result. A call that failed is never stored, so it fails again the next time.
 */
interface ResultCache {

    /** True when this cache stores results at all. */
    val enabled: Boolean

    /** The stored result of [code], or null when there is none. */
    fun lookup(code: String): Stored?

    /** Stores [result] as the result of [code]. */
    fun store(code: String, result: String?)

    /** A stored result. [result] is null for code that gave no result. */
    class Stored(val result: String?)

    companion object {
        /** A cache that stores nothing, for environments with caching switched off. */
        val NONE: ResultCache = object : ResultCache {
            override val enabled = false
            override fun lookup(code: String): Stored? = null
            override fun store(code: String, result: String?) = Unit
        }
    }
}

/**
 * A [ResultCache] bounded by a number of entries and by memory, which drops the least recently used entries first.
 *
 * The memory of an entry is estimated as two bytes per character of the code text and of the result, plus
 * [ENTRY_OVERHEAD_BYTES] for the objects around them. An entry larger than [maxBytes] on its own is not stored.
 *
 * Every method holds the lock of the cache only for the work on the map and never during a call of Python. Branches
 * that run at the same time under TrueParallel therefore never wait for each other's Python call. Two branches that
 * miss the same text at the same moment both run it, and the later one stores its result over the earlier one.
 *
 * @property maxEntries the most entries the cache holds; at least 1.
 * @property maxBytes the most memory the entries may take, in the estimate above; at least 1.
 */
class LruResultCache(private val maxEntries: Int, private val maxBytes: Long) : ResultCache {

    init {
        require(maxEntries > 0) { "The result cache needs room for at least one entry, not $maxEntries" }
        require(maxBytes > 0) { "The result cache needs at least one byte, not $maxBytes" }
    }

    override val enabled = true

    /** The entries in the order of their last use, the least recently used first. Guarded by this cache. */
    private val entries = LinkedHashMap<String, ResultCache.Stored>(16, 0.75f, true)

    /** The estimated memory of all [entries]. Guarded by this cache. */
    private var bytes = 0L

    /** The number of entries. */
    val size: Int
        @Synchronized get() = entries.size

    /** The estimated memory of all entries, in bytes. */
    val memoryBytes: Long
        @Synchronized get() = bytes

    @Synchronized
    override fun lookup(code: String): ResultCache.Stored? = entries[code]

    @Synchronized
    override fun store(code: String, result: String?) {
        val size = sizeOf(code, result)
        if (size > maxBytes) return
        entries.put(code, ResultCache.Stored(result))?.let { bytes -= sizeOf(code, it.result) }
        bytes += size
        val oldest = entries.entries.iterator()
        while (entries.size > maxEntries || bytes > maxBytes) {
            val eldest = oldest.next()
            bytes -= sizeOf(eldest.key, eldest.value.result)
            oldest.remove()
        }
    }

    private companion object {
        /** The estimated memory of the objects around the two texts of an entry. */
        const val ENTRY_OVERHEAD_BYTES = 96L

        fun sizeOf(code: String, result: String?): Long =
            ENTRY_OVERHEAD_BYTES + 2L * (code.length + (result?.length ?: 0))
    }
}
