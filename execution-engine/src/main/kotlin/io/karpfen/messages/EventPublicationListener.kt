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
package io.karpfen.io.karpfen.messages

/**
 * Learns about every event published on an [EventBus].
 *
 * Every event reaches a bus through [EventBus.publish]: events from outside clients on the environment thread, and
 * events raised by `EVENT` actions on the engine thread while their action block runs. A listener is therefore called
 * on either thread and must not block.
 */
fun interface EventPublicationListener {

    /** Called once for every published [event], after it entered its domain bucket with its effective time to live. */
    fun onPublished(event: Event)
}
