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
 * Callback interface for model data changes.
 *
 * Whenever a [DataObject]'s property or relation is updated, the [ModelQueryProcessor]
 * calls all registered [ModelChangePublisher] instances so that observers (e.g., WebSocket
 * clients) can be notified.
 *
 * @see ModelQueryProcessor.addChangePublisher
 */
fun interface ModelChangePublisher {
    /**
     * Called after a property or relation on the given [objectId] has been changed.
     *
     * @param objectId   The id of the DataObject that changed.
     * @param jsonValue  A JSON string representation of the updated DataObject.
     */
    fun onObjectChanged(objectId: String, jsonValue: String)
}

