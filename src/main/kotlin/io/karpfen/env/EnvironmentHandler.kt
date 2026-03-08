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
package io.karpfen.env

import io.karpfen.EngineTraceLogger
import io.karpfen.websocket.ClientSessionManager
import java.util.concurrent.ConcurrentHashMap

object EnvironmentHandler {

    val envs: MutableMap<String, Environment> = ConcurrentHashMap()
    val activeEnvs: MutableMap<String, Environment> = ConcurrentHashMap()

    val executionThreads: MutableMap<String, EnvironmentThread> = ConcurrentHashMap()

    val clientSessionManager = ClientSessionManager()

    /** Directory for engine trace log files. Null disables file logging. */
    var traceLogDirectory: String? = null

    /** Whether engine trace entries should also be printed to console. */
    var traceConsoleOutput: Boolean = false

    /** Default tick delay (ms) applied to newly created environments, loaded from application.conf. */
    var defaultTickDelayMs: Int = 1000

    fun createEnv(key: String): Environment {
        val env = Environment(key, tickDelayMS = defaultTickDelayMs)
        envs[key] = env
        return env
    }

    fun getEnv(key: String): Environment? {
        return envs[key]
    }

    fun activeEnv(key: String) {
        val env = envs[key] ?: throw IllegalArgumentException("Environment with key '$key' does not exist.")
        activeEnvs[key] = env

        val traceLogger = if (traceLogDirectory != null || traceConsoleOutput) {
            val logFile = traceLogDirectory?.let { "$it/engine-trace-$key.log" }
            EngineTraceLogger(
                engineId = key,
                logFilePath = logFile,
                consoleOutput = traceConsoleOutput
            )
        } else null

        val envThread = EnvironmentThread(env, traceLogger)
        envThread.setup()
        executionThreads[key] = envThread
    }

    fun isActiveEnv(key: String): Boolean {
        return activeEnvs.containsKey(key)
    }

    fun registerClientSession(clientId: String, envKey: String): String {
        if (envs[envKey] == null) {
            throw IllegalArgumentException("Environment with key '$envKey' does not exist.")
        }
        return clientSessionManager.registerClientAccess(clientId, envKey)
    }

    fun startEnvironmentThread(envKey: String) {
        val envThread = executionThreads[envKey]
            ?: throw IllegalStateException("Environment with key '$envKey' has no execution thread")
        val thread = Thread(envThread, "EnvironmentThread-$envKey")
        thread.isDaemon = false
        thread.start()
        println("[EnvironmentHandler] Started execution thread for environment $envKey")
    }

    fun stopEnvironmentThread(envKey: String) {
        val envThread = executionThreads[envKey]
            ?: throw IllegalStateException("Environment with key '$envKey' has no execution thread")
        envThread.stop()
        clientSessionManager.closeEnvironmentSessions(envKey)
        println("[EnvironmentHandler] Stopped execution thread for environment $envKey")
    }
}
