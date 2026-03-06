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
package io.karpfen

import instance.Model
import io.karpfen.io.karpfen.messages.Event
import kotlinx.coroutines.Runnable
import meta.Metamodel
import states.State
import states.StateMachine
import states.Transition
import java.util.Queue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class Engine(
    val metamodel: Metamodel,
    val model: Model,
    val statemachineMap: Map<String, StateMachine>,
    val tickDelayMS: Int
) {

    private val eventQueue: BlockingQueue<Event> = LinkedBlockingQueue()
    private val dataObservationListeners: MutableMap<String, MutableList<DataObservationListener>> = mutableMapOf()
    private var messageOutChannel: Channel? = null

    private var isRunning: Boolean = false
    private var executionThread: Thread? = null

    fun getEventQueue(): Queue<Event> {
        return eventQueue
    }

    fun setMessageOutChannel(channel: Channel) {
        this.messageOutChannel = channel
    }

    fun registerDataObservationListener(objectId: String, listener: DataObservationListener) {
        val listeners = dataObservationListeners.getOrDefault(objectId, mutableListOf())
        listeners.add(listener)
        dataObservationListeners[objectId] = listeners
    }

    fun start() {
        if (!isRunning) {
            executionThread = thread(start = false) {

                val stateStack: MutableList<State> = mutableListOf()
                val notEnteredSubstack: MutableList<State> = mutableListOf()
                var nextTransition: Transition? = null

                stateStack.addAll(listOf()) //TODO find initial state and its stack

                while (isRunning) {
                    if (nextTransition != null){
                        //0. set up the new state stack according to the transition's target state
                        nextTransition = null
                    }
                    //1. evaluate the state entry block ---> what's with the stack?
                    //2. check if a transition is executable
                    //3. if yes, execute the transition and update the state stack accordingly
                    if (nextTransition == null) {
                        //4. evaluate the state do block
                        //5. check if a transition is executable
                        //6. if yes, execute the transition and update the state stack accordingly
                        if (nextTransition == null) {
                            //wait until an event or something happens which may trigger a transition
                            while(nextTransition == null) {
                                Thread.sleep(tickDelayMS.toLong())
                                //check which transition is the next.
                            }
                        }
                    }
                    Thread.sleep(tickDelayMS.toLong())
                }

                return@thread
            }
            isRunning = true
            executionThread?.start()
        }
    }

    fun stop() {
        isRunning = false
        Thread.sleep(5000)
        executionThread?.interrupt()
    }
}