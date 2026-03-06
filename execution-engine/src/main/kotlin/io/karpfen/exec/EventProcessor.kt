package io.karpfen.io.karpfen.exec

import io.karpfen.io.karpfen.messages.Event
import java.util.concurrent.BlockingQueue

class EventProcessor(val eventQueue: BlockingQueue<Event>) {

    fun pushEvent(event: Event) {
        eventQueue.put(event)
    }

    fun purgeOutdatedEvents(livingLongerThanMS: Long) {
        val cutoffTimestamp = System.currentTimeMillis() - livingLongerThanMS
        val iterator = eventQueue.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (event.timestamp < cutoffTimestamp) {
                iterator.remove()
            }
        }
    }

}