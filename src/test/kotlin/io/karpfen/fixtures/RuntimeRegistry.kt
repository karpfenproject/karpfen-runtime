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
package io.karpfen.fixtures

import io.karpfen.env.EnvironmentHandler

/**
 * Resets the runtime's process-wide environment registry around tests that use it.
 *
 * [EnvironmentHandler] is a singleton shared by every test of the server module, and its session manager has one
 * outgoing queue. Tests that use the registry call [reset] before and after each test: it stops every execution
 * thread, clears the environment maps, and drains the queued messages so no test sees another test's pushes.
 */
object RuntimeRegistry {

    /** Stops every execution thread, clears the registry maps and drains the shared outgoing queue. */
    fun reset() {
        EnvironmentHandler.executionThreads.values.forEach { thread -> runCatching { thread.stop() } }
        EnvironmentHandler.envs.clear()
        EnvironmentHandler.activeEnvs.clear()
        EnvironmentHandler.executionThreads.clear()
        drainOutgoingQueue()
    }

    /** Removes every message queued so far from the shared outgoing queue. */
    fun drainOutgoingQueue() {
        do {
            val message = EnvironmentHandler.clientSessionManager.nextOutgoingMessage(0)
        } while (message != null)
    }
}
