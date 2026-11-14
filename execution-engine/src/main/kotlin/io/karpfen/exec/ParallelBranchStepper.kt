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

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Steps the branches of a split state machine at the same time, each branch on a thread of its own.
 *
 * Every branch gets one thread when it does its first parallel step, and keeps that thread for as long as the branch
 * lives: the thread does the step of its branch in every tick ("tick looped"). [step] hands each branch of a context
 * its step through the [branchRunner] and returns only when every branch has finished it, so the tick of the
 * environment still ends after all of its steps and keeps its number and meaning. A branch never splits while its
 * context is parallel, so every step is a plain [BranchRunner.tick] whose result needs no further handling.
 *
 * The stepper is used by the engine thread only. It keeps no reference to the contexts; the engine tells it after
 * each tick which branches still live ([retain]) and the threads of the others end then. [close] ends every thread
 * and is called when the engine thread ends, so no thread of a branch outlives its engine.
 *
 * A thread of a branch is a daemon thread named `Branch-<id>`, where the id is that of the branch's event processor.
 *
 * @property branchRunner runs one step of one branch; it holds no state of its own, so the threads share it.
 */
class ParallelBranchStepper(private val branchRunner: BranchRunner) {

    /** The thread of every branch that did a parallel step and has not ended, by the identity of the branch. */
    private val threads = IdentityHashMap<Branch, ExecutorService>()

    /** The number of branch threads that are alive now. */
    val threadCount: Int
        get() = threads.size

    /**
     * Runs the step of every branch of [ctx] on the thread of that branch, all at the same time, and waits until each
     * has finished. When steps fail, the first failure is thrown once all steps have ended, with the later ones
     * attached as suppressed. When the engine thread is interrupted while it waits, every thread of this step is
     * interrupted and awaited before the interrupt is passed on, so no step runs on after the tick has left.
     */
    fun step(ctx: SMContext) {
        val branches = ctx.branches.toList()
        val running = branches.map { branch -> threadOf(branch).submit(Callable { branchRunner.tick(ctx, branch, false) }) }
        try {
            awaitAll(running)
        } catch (interrupt: InterruptedException) {
            branches.mapNotNull { threads.remove(it) }.forEach(::end)
            Thread.currentThread().interrupt()
            throw interrupt
        }
    }

    /**
     * Ends the threads of every branch that is not in [live]. The engine calls it after each tick with the branches
     * of all its contexts, or with none while the feature is off. The ended threads are idle at that moment, so they
     * end at once.
     */
    fun retain(live: Collection<Branch>) {
        val keep = Collections.newSetFromMap(IdentityHashMap<Branch, Boolean>()).apply { addAll(live) }
        val ended = threads.keys.filter { it !in keep }
        ended.mapNotNull { threads.remove(it) }.forEach(::end)
    }

    /** Ends the thread of every branch. A step that still runs is interrupted. */
    fun close() {
        val all = threads.values.toList()
        threads.clear()
        all.forEach { it.shutdownNow() }
        all.forEach(::awaitEnd)
    }

    /** The thread of [branch], started on its first use. */
    private fun threadOf(branch: Branch): ExecutorService = threads.getOrPut(branch) {
        val name = "Branch-${branch.eventProcessor.engineId}"
        Executors.newSingleThreadExecutor { work -> Thread(work, name).apply { isDaemon = true } }
    }

    /** Waits for every step in [running]; rethrows the first failure after all have ended. */
    private fun awaitAll(running: List<Future<*>>) {
        var failure: Throwable? = null
        for (step in running) {
            try {
                step.get()
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                val first = failure
                if (first == null) failure = cause else first.addSuppressed(cause)
            }
        }
        failure?.let { throw it }
    }

    /** Ends one branch thread: interrupts a step that may still run and waits for the thread to end. */
    private fun end(thread: ExecutorService) {
        thread.shutdownNow()
        awaitEnd(thread)
    }

    /** Waits at most [END_TIMEOUT_MS] for [thread] to end, keeping an interrupt of the caller for later. */
    private fun awaitEnd(thread: ExecutorService) {
        val interrupted = Thread.interrupted()
        try {
            if (!thread.awaitTermination(END_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                System.err.println("[ParallelBranchStepper] A branch thread did not end within $END_TIMEOUT_MS ms")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        /** How long ending a branch thread waits for it. */
        const val END_TIMEOUT_MS = 10_000L
    }
}
