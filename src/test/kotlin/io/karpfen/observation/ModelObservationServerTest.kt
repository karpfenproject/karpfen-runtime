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

import io.karpfen.io.karpfen.exec.TickGate
import io.karpfen.server.HTTPRoutes
import io.karpfen.server.KtorServer
import io.karpfen.websocket.ClientSessionManager
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the observation routes on the runtime's real Netty server, which binds a free local port: a snapshot that
 * waits for a hung tick holds up no call of another environment.
 *
 * The server pins each connection to one of its call event loops in turn, one loop per processor. Every request here
 * opens a connection of its own, so more than twice as many requests in a row as there are processors are sure to share
 * the event loop of the waiting snapshot's connection.
 */
class ModelObservationServerTest {

    /** The tick gate of the environment whose tick hangs until the test releases it. */
    private val hungGate = TickGate()

    /** Released by the test to end the hung tick. */
    private val tickReleased = CountDownLatch(1)

    /** Counted down once a snapshot of the hung environment waits for its tick boundary. */
    private val snapshotWaiting = CountDownLatch(1)

    @Test
    fun `calls for another environment are answered while a snapshot waits for a hung tick`() {
        val hungTick = holdTick()
        val port = ServerSocket(0).use { it.localPort }
        val server = KtorServer(
            port = port,
            host = HOST,
            sessionManager = ClientSessionManager(),
            observationRoutes = routes()
        )
        server.start()
        try {
            val waiting = CompletableFuture.supplyAsync { statusLine(port, "/observatory/model?envKey=$HUNG") }
            assertTrue(snapshotWaiting.await(ANSWER_WAIT_MS, TimeUnit.MILLISECONDS), "The snapshot never began to wait")

            repeat(2 * Runtime.getRuntime().availableProcessors() + 2) {
                assertEquals(OK, statusLine(port, "/observatory/model?envKey=$QUICK"))
            }

            assertFalse(waiting.isDone, "The hung tick must still hold the waiting snapshot")
            tickReleased.countDown()
            assertEquals(OK, waiting.get(ANSWER_WAIT_MS, TimeUnit.MILLISECONDS))
        } finally {
            tickReleased.countDown()
            hungTick.join()
            server.stop()
        }
    }

    /** The observation routes over a hung and a quick running environment, with a tick wait longer than the test. */
    private fun routes(): ModelObservationRoutes {
        val environments = FakeEnvironments().apply {
            create(HUNG)
            activate(HUNG, GatedEnvironment(HUNG, hungGate, snapshotWaiting))
            create(QUICK)
            activate(QUICK, GatedEnvironment(QUICK, TickGate(), CountDownLatch(0)))
        }
        return ModelObservationRoutes(
            ModelSnapshotService(environments, TICK_WAIT_MS),
            ModelSubscriptionService(environments, ClientSessionManager()),
            HTTPRoutes::respondWithError
        )
    }

    /** Starts the hung tick: a thread that holds [hungGate] until [tickReleased]; returns once the gate is held. */
    private fun holdTick(): Thread {
        val held = CountDownLatch(1)
        val tick = thread(name = "hung-tick") {
            hungGate.duringTick {
                held.countDown()
                tickReleased.await()
            }
        }
        assertTrue(held.await(ANSWER_WAIT_MS, TimeUnit.MILLISECONDS), "The hung tick never began")
        return tick
    }

    /**
     * Sends `GET [path]` on a connection of its own, which the server closes after its answer, and returns the status
     * line of the answer.
     */
    private fun statusLine(port: Int, path: String): String =
        Socket(HOST, port).use { socket ->
            socket.soTimeout = ANSWER_WAIT_MS.toInt()
            socket.getOutputStream().apply {
                write("GET $path HTTP/1.1\r\nHost: $HOST\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            socket.getInputStream().readBytes().toString(Charsets.US_ASCII).lineSequence().first()
        }

    /**
     * A running environment whose snapshots are read through [gate]; [reading] is counted down whenever a snapshot
     * begins to wait for a tick boundary.
     */
    private class GatedEnvironment(
        private val key: String,
        private val gate: TickGate,
        private val reading: CountDownLatch
    ) : ObservedEnvironment {

        /** Always running. */
        override val phase = EnvironmentPhase.RUNNING

        /** Announces the wait on [reading], then reads an empty snapshot at the next boundary of [gate]. */
        override fun snapshotBetweenTicks(timeoutMs: Long): ModelSnapshot? {
            reading.countDown()
            return gate.betweenTicks(timeoutMs) { ModelSnapshot(key, phase, 0L, emptyMap(), emptyList()) }
        }
    }

    private companion object {
        /** The address the server binds and the test connects to. */
        const val HOST = "127.0.0.1"

        /** The environment whose tick hangs. */
        const val HUNG = "env-hung"

        /** The environment whose snapshots are answered at once. */
        const val QUICK = "env-quick"

        /** The status line of a successful answer. */
        const val OK = "HTTP/1.1 200 OK"

        /** The generous wait for any answer; a call that shares the hung event loop would exceed it. */
        const val ANSWER_WAIT_MS = 30_000L

        /** The snapshots' wait for a tick boundary, longer than the whole test, so the hung tick never times out. */
        const val TICK_WAIT_MS = 120_000L
    }
}
