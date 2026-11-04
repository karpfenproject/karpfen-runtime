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

/**
 * Where an environment is in its life, as a model snapshot reports it. The runtime keeps no phase field; the phase is
 * derived from the registry and the environment's execution thread.
 *
 * @property wireName the name the snapshot writes as its `phase` member.
 */
enum class EnvironmentPhase(val wireName: String) {

    /** Created and being configured: no execution thread exists yet. */
    CONFIGURING("configuring"),

    /** Activated: the execution thread is built and set up but was never started. */
    ACTIVATED("activated"),

    /** The engine thread is alive: started and not stopped, or a start or a stop is still under way. */
    RUNNING("running"),

    /**
     * Started and not stopped, but ended on its own: an exception or error ended the engine thread, or it ended the
     * event loop that hands client events to the bus, which stops the engine.
     */
    HALTED("halted"),

    /** Stopped through the stop route and the engine thread has ended; the model stays as the engine left it. */
    STOPPED("stopped")
}
