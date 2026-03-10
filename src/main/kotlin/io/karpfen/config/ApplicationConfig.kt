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

import java.io.File

/**
 * Server configuration loaded from application.conf
 */
data class ServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8080
)

data class WebSocketConfig(
    val queueTimeoutMs: Long = 1000,
    val enabled: Boolean = true
)

data class LoggingConfig(
    val level: String = "INFO",
    val consoleOutput: Boolean = true
)

data class EngineTracingConfig(
    val enabled: Boolean = false,
    val logDirectory: String? = null,
    val consoleOutput: Boolean = false,
    val simpleTrace: Boolean = true
)

data class EngineConfig(
    /** Default tick delay in milliseconds applied to every newly created environment. */
    val defaultTickDelayMs: Int = 1000,
    /** Default event TTL in milliseconds applied to every newly created environment. 0 means live forever. */
    val defaultEventTtlMs: Long = 1000,
    /** When true (default), events are consumed only when a transition fires. When false, consumed on condition read. */
    val eventConsumptionOnFire: Boolean = true
)

data class ApplicationConfig(
    val server: ServerConfig = ServerConfig(),
    val websocket: WebSocketConfig = WebSocketConfig(),
    val logging: LoggingConfig = LoggingConfig(),
    val engineTracing: EngineTracingConfig = EngineTracingConfig(),
    val engine: EngineConfig = EngineConfig()
) {
    companion object {
        /**
         * Loads configuration from application.conf file in the project root
         */
        fun loadFromFile(filePath: String = "application.conf"): ApplicationConfig {
            val file = File(filePath)

            if (!file.exists()) {
                println("[Config] File not found: $filePath, using default configuration")
                return ApplicationConfig()
            }

            return try {
                val content = file.readText()
                parseConfig(content)
            } catch (e: Exception) {
                println("[Config] Error loading configuration: ${e.message}, using default configuration")
                ApplicationConfig()
            }
        }

        private fun parseConfig(content: String): ApplicationConfig {
            var host = "127.0.0.1"
            var port = 8080
            var queueTimeoutMs = 1000L
            var wsEnabled = true
            var logLevel = "INFO"
            var consoleOutput = true
            var tracingEnabled = false
            var tracingLogDirectory: String? = null
            var tracingConsoleOutput = false
            var simpleTrace = true
            var defaultTickDelayMs = 1000
            var defaultEventTtlMs = 1000L
            var eventConsumptionOnFire = true

            // Simple parsing logic for the conf file
            content.lines().forEach { line ->
                val trimmed = line.trim()

                // Skip comments and empty lines
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

                // Normalize whitespace around '=' so aligned values ("key  = value") parse correctly.
                // Also strip inline comments after the value (e.g. "value   # comment").
                val normalized = trimmed
                    .replace(Regex("\\s*=\\s*"), " = ")
                    .substringBefore("  #")  // strip "  # inline comment" suffix
                    .substringBefore("\t#")  // strip tab-prefixed inline comment
                    .trim()

                when {
                    normalized.contains("host =") -> {
                        host = extractValue(normalized)
                    }
                    normalized.contains("port =") -> {
                        port = extractValue(normalized).toIntOrNull() ?: 8080
                    }
                    normalized.contains("queueTimeoutMs =") -> {
                        queueTimeoutMs = extractValue(normalized).toLongOrNull() ?: 1000L
                    }
                    normalized.contains("enabled =") && !normalized.startsWith("#") -> {
                        wsEnabled = extractValue(normalized).toBoolean()
                    }
                    normalized.contains("level =") -> {
                        logLevel = extractValue(normalized)
                    }
                    normalized.contains("consoleOutput =") -> {
                        consoleOutput = extractValue(normalized).toBoolean()
                    }
                    normalized.contains("tracingEnabled =") -> {
                        tracingEnabled = extractValue(normalized).toBoolean()
                    }
                    normalized.contains("tracingLogDirectory =") -> {
                        tracingLogDirectory = extractValue(normalized).ifBlank { null }
                    }
                    normalized.contains("tracingConsoleOutput =") -> {
                        tracingConsoleOutput = extractValue(normalized).toBoolean()
                    }
                    normalized.contains("simpleTrace =") -> {
                        simpleTrace = extractValue(normalized).toBoolean()
                    }
                    normalized.contains("defaultTickDelayMs =") -> {
                        defaultTickDelayMs = extractValue(normalized).toIntOrNull() ?: 1000
                    }
                    normalized.contains("defaultEventTtlMs =") -> {
                        defaultEventTtlMs = extractValue(normalized).toLongOrNull() ?: 1000L
                    }
                    normalized.contains("eventConsumptionOnFire =") -> {
                        eventConsumptionOnFire = extractValue(normalized).toBoolean()
                    }
                }
            }

            return ApplicationConfig(
                server = ServerConfig(host = host, port = port),
                websocket = WebSocketConfig(queueTimeoutMs = queueTimeoutMs, enabled = wsEnabled),
                logging = LoggingConfig(level = logLevel, consoleOutput = consoleOutput),
                engineTracing = EngineTracingConfig(
                    enabled = tracingEnabled,
                    logDirectory = tracingLogDirectory,
                    consoleOutput = tracingConsoleOutput,
                    simpleTrace = simpleTrace
                ),
                engine = EngineConfig(defaultTickDelayMs = defaultTickDelayMs, defaultEventTtlMs = defaultEventTtlMs, eventConsumptionOnFire = eventConsumptionOnFire)
            )
        }

        private fun extractValue(line: String): String {
            val parts = line.split("=")
            if (parts.size < 2) return ""

            var value = parts[1].trim()

            // Remove quotes if present
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            }

            return value
        }
    }
}

