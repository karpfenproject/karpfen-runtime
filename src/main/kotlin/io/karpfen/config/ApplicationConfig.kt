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

data class ApplicationConfig(
    val server: ServerConfig = ServerConfig(),
    val websocket: WebSocketConfig = WebSocketConfig(),
    val logging: LoggingConfig = LoggingConfig()
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

            // Simple parsing logic for the conf file
            content.lines().forEach { line ->
                val trimmed = line.trim()

                // Skip comments and empty lines
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

                when {
                    trimmed.contains("host =") -> {
                        host = extractValue(trimmed)
                    }
                    trimmed.contains("port =") -> {
                        port = extractValue(trimmed).toIntOrNull() ?: 8080
                    }
                    trimmed.contains("queueTimeoutMs =") -> {
                        queueTimeoutMs = extractValue(trimmed).toLongOrNull() ?: 1000L
                    }
                    trimmed.contains("enabled =") && !trimmed.startsWith("#") -> {
                        wsEnabled = extractValue(trimmed).toBoolean()
                    }
                    trimmed.contains("level =") -> {
                        logLevel = extractValue(trimmed)
                    }
                    trimmed.contains("consoleOutput =") -> {
                        consoleOutput = extractValue(trimmed).toBoolean()
                    }
                }
            }

            return ApplicationConfig(
                server = ServerConfig(host = host, port = port),
                websocket = WebSocketConfig(queueTimeoutMs = queueTimeoutMs, enabled = wsEnabled),
                logging = LoggingConfig(level = logLevel, consoleOutput = consoleOutput)
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

