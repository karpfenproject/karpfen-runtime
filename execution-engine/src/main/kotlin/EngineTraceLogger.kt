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
package io.karpfen

import java.io.File
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Structured trace logger for the state machine execution engine.
 *
 * Each [EngineTraceLogger] is bound to a single engine instance and records
 * every significant execution event (ticks, state transitions, entry/do block
 * executions, errors) as structured [TraceEntry] objects.
 *
 * Traces can be:
 * - Collected in-memory for programmatic inspection (e.g. in tests)
 * - Written to a dedicated log file (configured via [logFilePath])
 * - Printed to stdout (when [consoleOutput] is true)
 *
 * Thread safety: All collections are thread-safe. File writes are synchronized.
 */
class EngineTraceLogger(
    val engineId: String,
    val logFilePath: String? = null,
    val consoleOutput: Boolean = true
) {
    /**
     * Represents a single trace entry produced by the engine.
     */
    data class TraceEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val engineId: String,
        val modelElementId: String,
        val eventType: TraceEventType,
        val message: String,
        val details: Map<String, String> = emptyMap()
    ) {
        fun format(): String {
            val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp))
            val detailStr = if (details.isNotEmpty()) " | ${details.entries.joinToString(", ") { "${it.key}=${it.value}" }}" else ""
            return "[$time] [$engineId] [$modelElementId] ${eventType.name}: $message$detailStr"
        }
    }

    enum class TraceEventType {
        ENGINE_START,
        ENGINE_STOP,
        TICK_START,
        TICK_END,
        INITIAL_STATE,
        STATE_ENTRY_EXEC,
        STATE_ENTRY_SKIP,
        STATE_DO_EXEC,
        STATE_DO_SKIP,
        TRANSITION_FIRED,
        TRANSITION_SKIPPED_LOOP,
        TRANSITION_CHECK,
        EVENT_RECEIVED,
        EVENT_CONSUMED,
        ACTION_ERROR,
        ENGINE_ERROR
    }

    /** In-memory trace log — thread-safe for concurrent reads/writes. */
    private val _traces = CopyOnWriteArrayList<TraceEntry>()

    /** External listeners notified on each new trace entry. */
    private val traceListeners = CopyOnWriteArrayList<(TraceEntry) -> Unit>()

    private var fileWriter: PrintWriter? = null
    private val fileLock = Any()

    init {
        if (logFilePath != null) {
            try {
                val file = File(logFilePath)
                file.parentFile?.mkdirs()
                fileWriter = PrintWriter(file.bufferedWriter())
                fileWriter?.println("# Engine Trace Log — Engine: $engineId — Started: ${Instant.now()}")
                fileWriter?.flush()
            } catch (e: Exception) {
                System.err.println("[EngineTraceLogger] Failed to open log file '$logFilePath': ${e.message}")
            }
        }
    }

    fun log(
        modelElementId: String,
        eventType: TraceEventType,
        message: String,
        details: Map<String, String> = emptyMap()
    ) {
        val entry = TraceEntry(
            engineId = engineId,
            modelElementId = modelElementId,
            eventType = eventType,
            message = message,
            details = details
        )
        _traces.add(entry)

        val formatted = entry.format()

        if (consoleOutput) {
            println(formatted)
        }

        if (fileWriter != null) {
            synchronized(fileLock) {
                fileWriter?.println(formatted)
                fileWriter?.flush()
            }
        }

        for (listener in traceListeners) {
            try {
                listener(entry)
            } catch (_: Exception) { }
        }
    }

    /**
     * Registers a listener that is called for every new trace entry.
     */
    fun addTraceListener(listener: (TraceEntry) -> Unit) {
        traceListeners.add(listener)
    }

    /**
     * Returns all trace entries matching the given event type.
     */
    fun tracesOfType(type: TraceEventType): List<TraceEntry> =
        _traces.filter { it.eventType == type }

    /**
     * Returns all transitions that were fired, as a list of "fromState -> toState" strings.
     */
    fun firedTransitions(): List<String> =
        tracesOfType(TraceEventType.TRANSITION_FIRED).map { it.message }

    /**
     * Returns the sequence of states that were entered (via ENTRY execution).
     */
    fun enteredStates(): List<String> =
        tracesOfType(TraceEventType.STATE_ENTRY_EXEC).map {
            it.details["state"] ?: it.message
        }

    /**
     * Returns all error trace entries.
     */
    fun errors(): List<TraceEntry> =
        _traces.filter { it.eventType == TraceEventType.ACTION_ERROR || it.eventType == TraceEventType.ENGINE_ERROR }

    fun close() {
        synchronized(fileLock) {
            fileWriter?.println("# Engine Trace Log ended: ${Instant.now()}")
            fileWriter?.close()
            fileWriter = null
        }
    }
}

