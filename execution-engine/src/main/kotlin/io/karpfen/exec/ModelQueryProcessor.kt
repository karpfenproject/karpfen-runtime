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

import instance.DataObject
import instance.Model
import meta.Metamodel

class ModelQueryProcessor(val metamodel: Metamodel, val model: Model) {

    fun getDataObjectById(id: String): DataObject {
        //TODO find the data object with the given id in the model and return it
        throw NotImplementedError("getDataObjectById is not implemented yet")
    }

     fun getValueOfThing(startObjectId: String, argumentPath: List<String>): Any? {
        return null
    }

}