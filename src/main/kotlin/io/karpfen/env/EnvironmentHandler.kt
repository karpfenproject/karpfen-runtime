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
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.websocket.ClientSessionManager
import io.ktor.websocket.CloseReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The registry of the environments of the runtime: the configured ones in [envs], the activated ones in [activeEnvs]
 * with their [executionThreads], and the [clientSessionManager] with the WebSocket clients of all of them.
 *
 * Creating, activating, registering clients and dropping take the [registryLock] for their changes of the registry,
 * so an environment that is dropped leaves nothing behind that one of those calls adds at the same time. Ending the
 * engine of a dropped environment, which may take a while, happens outside the lock.
 */
object EnvironmentHandler {

    /** The close code and reason of the sockets of a dropped environment. */
    val DROPPED: CloseReason = CloseReason(CloseReason.Codes.NORMAL, "Environment dropped")

    /** The close code and reason of the sockets of every environment when the runtime ends. */
    val RUNTIME_STOPPED: CloseReason = CloseReason(CloseReason.Codes.GOING_AWAY, "Runtime stopped")

    /** Guards the changes of the registry that must not interleave with a drop; see the class description. */
    private val registryLock = Any()

    /** The milliseconds of the latest key that [createUniqueEnv] handed out; keys only grow, so none comes twice. */
    private val lastKeyMillis = AtomicLong(0L)

    val envs: MutableMap<String, Environment> = ConcurrentHashMap()
    val activeEnvs: MutableMap<String, Environment> = ConcurrentHashMap()

    val executionThreads: MutableMap<String, EnvironmentThread> = ConcurrentHashMap()

    val clientSessionManager = ClientSessionManager()

    /** Directory for engine trace log files. Null disables file logging. */
    var traceLogDirectory: String? = null

    /** Whether engine trace entries should also be printed to console. */
    var traceConsoleOutput: Boolean = false

    /** When true, observatory trace messages use a compact format. */
    var simpleTrace: Boolean = true

    /** Default tick delay (ms) applied to newly created environments, loaded from application.conf. */
    var defaultTickDelayMs: Int = 1000

    /** Default event TTL (ms) applied to newly created environments, loaded from application.conf. 0 means live forever. */
    var defaultEventTtlMs: Long = 1000

    /** When true (default), events are consumed only when a transition fires. When false, consumed on condition read. */
    var defaultEventConsumptionOnFire: Boolean = true

    /** Whether new environments answer repeated code from their result cache, loaded from application.conf. */
    var defaultResultCaching: Boolean = true

    /** The most results the cache of an environment holds, loaded from application.conf. */
    var resultCacheMaxEntries: Int = 10_000

    /** The most memory in bytes the results of the cache of an environment may take, loaded from application.conf. */
    var resultCacheMaxBytes: Long = 64L * 1024 * 1024

    /** Whether the code of new environments may start processes, loaded from application.conf. */
    var allowProcessCalls: Boolean = false

    /** Creates an environment under [key] with the configured defaults, replacing any environment of that key. */
    fun createEnv(key: String): Environment {
        val env = newEnvironment(key)
        envs[key] = env
        return env
    }

    /**
     * Creates an environment under a key never handed out before, `env-<epoch millis>`.
     *
     * The milliseconds of a key are the current time, or one more than those of the latest key when that is not
     * earlier, so the keys grow with every call and a key of a dropped environment never comes again, also when
     * several environments are created within one millisecond. The key is claimed with `putIfAbsent`, and a key taken
     * by [createEnv] is skipped.
     */
    fun createUniqueEnv(): Environment {
        while (true) {
            val millis = lastKeyMillis.updateAndGet { last -> maxOf(System.currentTimeMillis(), last + 1) }
            val env = newEnvironment("env-$millis")
            if (envs.putIfAbsent(env.key, env) == null) {
                return env
            }
        }
    }

    /** A new environment under [key] with the configured default tick delay, event time to live and consumption. */
    private fun newEnvironment(key: String): Environment =
        Environment(
            key,
            tickDelayMS = defaultTickDelayMs,
            eventTtlMs = defaultEventTtlMs,
            eventConsumptionOnFire = defaultEventConsumptionOnFire,
            resultCaching = defaultResultCaching,
            resultCacheMaxEntries = resultCacheMaxEntries,
            resultCacheMaxBytes = resultCacheMaxBytes,
            allowProcessCalls = allowProcessCalls,
            featureManager = FeatureManager()
        )

    fun getEnv(key: String): Environment? {
        return envs[key]
    }

    /**
     * Activates the environment [key]: freezes its configuration into an [EnvironmentThread] that is set up and stored,
     * ready to be started. The thread runs the state machines of the environment flattened with its fragments, see
     * [ComposedStateMachines]; a state machine that does not flatten refuses the activation with an
     * [IllegalStateException] before anything is claimed, so the environment stays configurable.
     *
     * Activation is atomic. The key is claimed in [activeEnvs] with `putIfAbsent`, so a second activation is refused
     * with an [IllegalStateException] and the first execution thread stays in charge. When building the trace logger
     * or the thread, or its setup, fails, the claim is removed and the trace logger closed before the exception
     * propagates, so the environment stays configurable.
     */
    fun activeEnv(key: String) = synchronized(registryLock) {
        val env = envs[key] ?: throw IllegalArgumentException("Environment with key '$key' does not exist.")
        if (activeEnvs.containsKey(key)) throw alreadyActive(key)
        val stateMachines = ComposedStateMachines.of(env)
        if (activeEnvs.putIfAbsent(key, env) != null) {
            throw alreadyActive(key)
        }
        var traceLogger: EngineTraceLogger? = null
        try {
            traceLogger = createTraceLogger(key)
            val envThread = EnvironmentThread(env, stateMachines, traceLogger)
            envThread.setup()
            executionThreads[key] = envThread
        } catch (e: Exception) {
            activeEnvs.remove(key, env)
            traceLogger?.close()
            throw e
        }
    }

    /** The refusal of a second activation of the environment [key]. */
    private fun alreadyActive(key: String) = IllegalStateException(
        "Environment with key $key is already active and cannot be modified in the intended way."
    )

    /**
     * Builds the trace logger of the environment [key], writing to the trace directory when one is configured. The
     * logger is built even when file and console tracing are disabled, so that observatory WebSocket listeners work.
     */
    private fun createTraceLogger(key: String): EngineTraceLogger {
        val logFile = traceLogDirectory?.let { "$it/engine-trace-$key.log" }
        val traceLogger = EngineTraceLogger(
            engineId = key,
            logFilePath = logFile,
            consoleOutput = traceConsoleOutput
        )
        return traceLogger
    }

    fun isActiveEnv(key: String): Boolean {
        return activeEnvs.containsKey(key)
    }

    /** Issues an access key for [clientId] in the environment [envKey]. */
    fun registerClientSession(clientId: String, envKey: String): String = synchronized(registryLock) {
        if (envs[envKey] == null) {
            throw IllegalArgumentException("Environment with key '$envKey' does not exist.")
        }
        clientSessionManager.registerClientAccess(clientId, envKey)
    }

    /**
     * Issues an access key for the observatory client [clientId] in the environment [envKey] and subscribes it to the
     * traces and states of [modelElement], `*` for all, in one step under the [registryLock].
     */
    fun registerObservatorySession(clientId: String, envKey: String, modelElement: String): String =
        synchronized(registryLock) {
            val accessKey = registerClientSession(clientId, envKey)
            clientSessionManager.subscribeToObservatory(envKey, clientId, modelElement)
            accessKey
        }

    /**
     * Drops the environment [envKey] for good, in every phase.
     *
     * Under the [registryLock] the key leaves [envs], [activeEnvs] and [executionThreads], and the
     * [clientSessionManager] forgets its access keys, sessions, subscriptions and queued messages. From then on every
     * call with the key finds no environment, and no push for it reaches a client. Outside the lock the execution thread
     * is disposed, which ends the event loop and closes the engine: its thread and the threads of branches end, a pause
     * of TickByTick or a halt at a breakpoint is released, the Python sessions end and the trace log is closed and
     * stays on disk. Messages the last tick enqueued meanwhile are discarded, and then the sockets of the clients are
     * closed with [DROPPED]. The call returns after all of that, normally within the rest of the running tick.
     *
     * A drop that comes while a stop or a start is under way waits for it. Of two drops of one environment at the same
     * time, one drops it and the other finds no environment.
     *
     * @throws IllegalArgumentException when there is no environment [envKey].
     */
    fun dropEnv(envKey: String) {
        val (envThread, sessions) = synchronized(registryLock) {
            envs.remove(envKey) ?: throw IllegalArgumentException("Environment with key $envKey does not exist")
            activeEnvs.remove(envKey)
            executionThreads.remove(envKey) to clientSessionManager.forgetEnvironment(envKey)
        }
        try {
            envThread?.dispose()
        } finally {
            clientSessionManager.discardOutgoing(envKey)
            clientSessionManager.closeSessions(sessions, DROPPED)
        }
        println("[EnvironmentHandler] Dropped environment $envKey")
    }

    /**
     * Starts the activated environment [envKey] through [EnvironmentThread.launch], which owns the environment's one
     * runner: starting a running environment changes nothing, starting a stopped one runs it again.
     */
    fun startEnvironmentThread(envKey: String) {
        val envThread = executionThreads[envKey]
            ?: throw IllegalStateException("Environment with key '$envKey' has no execution thread")
        if (envThread.launch()) {
            println("[EnvironmentHandler] Started execution thread for environment $envKey")
        } else {
            println("[EnvironmentHandler] Environment $envKey is already running")
        }
    }

    /**
     * Stops the activated environment [envKey] through [EnvironmentThread.stop]: a freeze that keeps everything for the
     * next start. The WebSocket connections of its clients stay open. Stopping an environment that does not run changes
     * nothing.
     */
    fun stopEnvironmentThread(envKey: String) {
        val envThread = executionThreads[envKey]
            ?: throw IllegalStateException("Environment with key '$envKey' has no execution thread")
        if (envThread.stop()) {
            println("[EnvironmentHandler] Stopped execution thread for environment $envKey")
        } else {
            println("[EnvironmentHandler] Environment $envKey is not running")
        }
    }

    /**
     * Ends every environment for good when the runtime shuts down: closes the WebSocket connections of their clients
     * with the close code 1001 and the reason `Runtime stopped`, and disposes every execution thread, which closes the
     * engines with their Python sessions and trace logs.
     */
    fun shutdown() {
        for ((key, envThread) in executionThreads) {
            clientSessionManager.closeEnvironmentSessions(key, RUNTIME_STOPPED)
            runCatching { envThread.dispose() }
        }
    }
}
