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

import io.karpfen.dsl.instance.DataObject

/**
 * Learns which objects enter and leave the model of a [ModelQueryProcessor].
 *
 * Every object of the model enters it through [ModelQueryProcessor.registerObjectTree], which a `SETOBJ` or an
 * `APPENDOBJ` on a `has` relation calls, and leaves it through [ModelQueryProcessor.dropObject], which `DROPOBJ` and a
 * `SETOBJ` that replaces the object of a `has` relation call. The processor reports both here, so a listener sees
 * every object that comes and goes, whatever action or caller caused it.
 *
 * Both methods are called on the thread that changes the model, while it holds the write lock of the model, once per
 * write operation. A listener must therefore be quick, must not read or write the model through the processor from
 * another thread while it waits for something, and must not take a lock that a holder of its own lock could hold
 * while it waits for the model lock. The model lock is always taken before the lock of a listener.
 *
 * @see ModelQueryProcessor.addStructureListener
 */
interface ModelStructureListener {

    /**
     * Called after [objects] entered the model in one operation: the object a rule created followed by every object it
     * holds, in the depth-first order of its `has` relations.
     */
    fun objectsAdded(objects: List<DataObject>)

    /** Called after the objects with [objectIds] left the model in one operation, an object and everything it held. */
    fun objectsRemoved(objectIds: Set<String>)
}
