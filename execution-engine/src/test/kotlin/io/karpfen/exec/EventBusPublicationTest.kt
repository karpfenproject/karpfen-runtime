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
package io.karpfen.exec

import io.karpfen.io.karpfen.exec.ActionProcessor
import io.karpfen.io.karpfen.exec.EventProcessor
import io.karpfen.io.karpfen.exec.MacroProcessor
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import io.karpfen.io.karpfen.messages.EventPublicationListener
import io.karpfen.io.karpfen.messages.EventSource
import org.junit.jupiter.api.Test
import states.actions.ActionOperationType
import states.actions.ActionRule
import states.actions.ValueActionRightSide
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the publication listeners of [EventBus]: which events they receive, with which time to live, and how a
 * failing listener is contained.
 */
class EventBusPublicationTest {

    /** A bus whose default time to live applies to events published without one. */
    private val bus = EventBus(defaultTtlMs = 3000L)

    @Test
    fun `every published event reaches each publication listener once with its effective time to live`() {
        val first = mutableListOf<Event>()
        val second = mutableListOf<Event>()
        bus.addPublicationListener(EventPublicationListener { first.add(it) })
        bus.addPublicationListener(EventPublicationListener { second.add(it) })

        bus.publish(Event("public", "start"))
        bus.publish(Event("public", "stop", ttlMs = 500L))

        assertEquals(listOf("start" to 3000L, "stop" to 500L), first.map { it.name to it.ttlMs })
        assertEquals(first, second)
        assertSame(bus.eventsOf("public", "start").single(), first[0])
        assertSame(bus.eventsOf("public", "stop").single(), first[1])
    }

    @Test
    fun `a failing publication listener neither loses the event nor silences other listeners`() {
        val received = mutableListOf<Event>()
        bus.addPublicationListener(EventPublicationListener { throw IllegalStateException("listener broke") })
        bus.addPublicationListener(EventPublicationListener { received.add(it) })

        val reported = captureStandardError { bus.publish(Event("public", "start")) }

        assertEquals(listOf("start"), received.map { it.name })
        assertEquals(1, bus.eventsOf("public", "start").size)
        assertTrue(reported.contains("listener broke"), "Reported on stderr: $reported")
    }

    @Test
    fun `events raised by actions reach the publication listeners`() {
        val received = mutableListOf<Event>()
        bus.addPublicationListener(EventPublicationListener { received.add(it) })
        val metamodel = TestFixtures.buildMetamodel()
        val model = TestFixtures.buildModel(metamodel)
        val modelQueryProcessor = ModelQueryProcessor(metamodel, model)
        val actions = ActionProcessor(
            MacroProcessor(metamodel, model, emptyList(), "turtle", modelQueryProcessor),
            modelQueryProcessor,
            EventProcessor("publication-test", bus),
            "turtle"
        )

        actions.executeRule(ActionRule(ActionOperationType.EVENT, "local", ValueActionRightSide("done")))

        val raised = received.single()
        assertEquals(listOf("local", "done"), listOf(raised.domain, raised.name))
        assertEquals(EventSource.INTERNAL, raised.source)
        assertEquals(3000L, raised.ttlMs)
    }

    /** Runs [action] while collecting what is printed on stderr, and returns it. */
    private fun captureStandardError(action: () -> Unit): String {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        try {
            action()
        } finally {
            System.setErr(original)
        }
        return captured.toString()
    }
}
