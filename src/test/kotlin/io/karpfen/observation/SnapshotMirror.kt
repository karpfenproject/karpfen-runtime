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

import org.json.JSONArray
import org.json.JSONObject

/**
 * A client-side copy of an environment's model built from a [ModelSnapshot] and kept current by applying pushes the
 * way a model subscriber does: an `objectChanged` value replaces every copy of the object in the tree, an
 * `objectDeleted` removes every copy, a `stateChange` replaces the machine's stack, and each push's revision becomes
 * the mirror's revision.
 */
class SnapshotMirror(snapshot: ModelSnapshot) {

    /** The root objects, parsed from the snapshot and changed by the applied pushes. */
    val roots: MutableList<JSONObject> = snapshot.roots.map { JSONObject(it) }.toMutableList()

    /** The active stack per machine, as the snapshot and the applied pushes reported them. */
    val activeStates: MutableMap<String, List<String>> = LinkedHashMap(snapshot.activeStates)

    /** The revision of the last observation the mirror contains. */
    var revision: Long = snapshot.revision
        private set

    /**
     * Applies one push of the given [messageType] with its [payload]; the push's revision must follow the mirror's
     * revision directly.
     *
     * @throws IllegalStateException when the revision shows that a push was lost or repeated.
     */
    fun apply(messageType: String, payload: JSONObject) {
        val pushRevision = payload.getLong("revision")
        check(pushRevision == revision + 1) { "Push at revision $pushRevision after revision $revision" }
        when (messageType) {
            "objectChanged" -> replace(payload.getString("objectId"), payload.getJSONObject("value"))
            "objectDeleted" -> remove(payload.getString("objectId"))
            "stateChange" -> activeStates[payload.getString("modelElementId")] =
                payload.getJSONArray("stack").map { it as String }
            else -> throw IllegalStateException("Unexpected push $messageType")
        }
        revision = pushRevision
    }

    /** True when the mirror holds the same roots, stacks and revision as [snapshot]. */
    fun matches(snapshot: ModelSnapshot): Boolean =
        revision == snapshot.revision &&
            activeStates == snapshot.activeStates &&
            roots.size == snapshot.roots.size &&
            roots.zip(snapshot.roots).all { (mirrored, written) -> mirrored.similar(JSONObject(written)) }

    /** Replaces every copy of the object [id] by [value], a root included. */
    private fun replace(id: String, value: JSONObject) {
        roots.replaceAll { root -> if (root.getString("__id__") == id) value else root }
        roots.forEach { replaceInside(it, id, value) }
    }

    /** Replaces every copy of the object [id] below [node] by [value]. */
    private fun replaceInside(node: Any, id: String, value: JSONObject) {
        when (node) {
            is JSONObject -> node.keySet().toList().forEach { key ->
                val child = node.get(key)
                if (child is JSONObject && child.optString("__id__") == id) node.put(key, value)
                else replaceInside(child, id, value)
            }
            is JSONArray -> (0 until node.length()).forEach { index ->
                val child = node.get(index)
                if (child is JSONObject && child.optString("__id__") == id) node.put(index, value)
                else replaceInside(child, id, value)
            }
        }
    }

    /** Removes every copy of the object [id]: roots are dropped, atomic relations become `null`, lists lose it. */
    private fun remove(id: String) {
        roots.removeIf { it.getString("__id__") == id }
        roots.forEach { removeInside(it, id) }
    }

    /** Removes every copy of the object [id] below [node]. */
    private fun removeInside(node: Any, id: String) {
        when (node) {
            is JSONObject -> node.keySet().toList().forEach { key ->
                val child = node.get(key)
                if (child is JSONObject && child.optString("__id__") == id) node.put(key, JSONObject.NULL)
                else removeInside(child, id)
            }
            is JSONArray -> (node.length() - 1 downTo 0).forEach { index ->
                val child = node.get(index)
                if (child is JSONObject && child.optString("__id__") == id) node.remove(index)
                else removeInside(child, id)
            }
        }
    }
}
