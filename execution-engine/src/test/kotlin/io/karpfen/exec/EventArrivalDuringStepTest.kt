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

import io.karpfen.Engine
import io.karpfen.EngineTraceLogger
import io.karpfen.EngineTraceLogger.TraceEventType
import io.karpfen.io.karpfen.messages.Event
import io.karpfen.io.karpfen.messages.EventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An event that arrives while a step looks at the transitions of its state machine is not burned by that step: it was
 * not there when the transitions looked for it, so it is offered to the next step and fires the transition that waits
 * for it.
 *
 * The test makes the arrival exact without timing: a `NOT LOOPING` transition that is skipped writes a trace entry in
 * the middle of the search for a transition, after the transition that waits for the event has looked, and the trace
 * listener publishes the event at that moment on the engine thread.
 */
class EventArrivalDuringStepTest {

    private var engine: Engine? = null

    @AfterEach
    fun close() {
        engine?.close()
    }

    @Test
    fun `an event that arrives while a step looks at the transitions fires in the next step`() {
        val metamodel = PythonFixtures.metamodel()
        val trace = EngineTraceLogger("arrival", consoleOutput = false)
        val engine = Engine(
            metamodel = metamodel,
            model = PythonFixtures.model(metamodel),
            statemachineMap = mapOf("a" to PythonFixtures.stateMachine(LAMP)),
            tickDelayMS = 1,
            eventBus = EventBus(),
            engineId = "arrival",
            traceLogger = trace
        ).also { engine = it }
        val sent = AtomicBoolean(false)
        val fired = CopyOnWriteArrayList<String>()
        trace.addTraceListener { entry ->
            if (entry.eventType == TraceEventType.TRANSITION_SKIPPED_LOOP && sent.compareAndSet(false, true)) {
                engine.receiveExternalEvent(Event("t", "go"))
            }
            if (entry.eventType == TraceEventType.TRANSITION_FIRED) fired += entry.message
        }

        engine.start()

        PythonFixtures.await("the transition of the event") { "idle -> done" in fired }
    }

    private companion object {
        /** `idle` waits for `go` before it loops; the loop is skipped from its second step on. */
        val LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "start" { }
                    STATE "idle" { }
                    STATE "done" { }
                }
                TRANSITIONS {
                    TRANSITION "start" -> "idle" { }
                    TRANSITION "idle" -> "done" { CONDITION { EVENT("t", "go") } }
                    TRANSITION "idle" -> "idle" NOT LOOPING { }
                }
            }
        """
    }
}
