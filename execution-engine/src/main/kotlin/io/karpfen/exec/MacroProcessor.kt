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
import states.Macro

class MacroProcessor(
    val metamodel: Metamodel,
    val model: Model,
    val macros: List<Macro>,
    val currentContextModelElement: String,
    val modelQueryProcessor: ModelQueryProcessor
) {

    val context: DataObject = modelQueryProcessor.getDataObjectById(currentContextModelElement)

    fun executeInlineMacro(code: String, args: List<Any>, expectedTarget: String): Any? {
        return null
    }

     fun executeFullMacro(macroName: String) {

    }

    fun runPythonCode(code: String, args: List<Any>): Any? {
        //TODO run the given python code as a subprocess with the given arguments and return the result
        return null
    }

}