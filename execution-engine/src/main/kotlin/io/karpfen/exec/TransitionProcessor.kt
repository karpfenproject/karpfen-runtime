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

import instance.Model
import meta.Metamodel
import states.StateMachine
import states.Transition
import java.awt.EventQueue

class TransitionProcessor(
    val stateMachine: StateMachine,
    val metamodel: Metamodel,
    val model: Model,
    val stateMachineAttachedToModelElement: String,
    val macroProcessor: MacroProcessor,
    val stateMachineQueryHelper: StateMachineQueryHelper,
    val modelQueryProcessor: ModelQueryProcessor){

    val transitions = stateMachine.transitions

    fun findFirstExecutableTransition(currentState: String, eventQueue: EventQueue): Transition? {
        //TODO find the first transition that can be executed based on the current state of the model and the conditions of the transitions
        return null
    }

}