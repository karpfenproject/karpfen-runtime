package io.karpfen

import instance.Model
import io.karpfen.io.karpfen.messages.Event
import meta.Metamodel
import states.StateMachine
import java.util.Queue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

class Engine(
    val metamodel: Metamodel,
    val model: Model,
    val statemachineMap: Map<String, StateMachine>,
    val tickDelayMS: Int
) {

    private val eventQueue: BlockingQueue<Event> = LinkedBlockingQueue()
    private val dataObservationListeners: MutableMap<String, MutableList<DataObservationListener>> = mutableMapOf()
    private var messageOutChannel: Channel? = null

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
        throw NotImplementedError("Engine execution is not yet implemented")
    }

    fun stop() {
        throw NotImplementedError("Engine execution is not yet implemented")
    }
}