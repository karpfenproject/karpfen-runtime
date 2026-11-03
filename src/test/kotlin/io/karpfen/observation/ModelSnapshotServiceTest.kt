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

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import instance.Model
import io.karpfen.fixtures.CleaningRobotExample
import io.karpfen.fixtures.RingExample
import io.karpfen.io.karpfen.exec.ModelChangePublisher
import io.karpfen.io.karpfen.exec.ModelQueryProcessor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Covers [ModelSnapshotService] with fake environments and real parsed models: rejections, configuring environments,
 * reads through the tick gate, and the object JSON of the roots.
 */
class ModelSnapshotServiceTest {

    /** The environments the service finds. */
    private val environments = FakeEnvironments()

    /** The service under test. */
    private val service = ModelSnapshotService(environments, TICK_WAIT_MS)

    @Test
    fun `an unknown environment is rejected`() {
        val refusal = assertFailsWith<IllegalArgumentException> { service.snapshot("env-404") }

        assertEquals("Environment with key env-404 does not exist", refusal.message)
    }

    @Test
    fun `a configuring environment without a model is rejected`() {
        environments.create(ENV)

        val refusal = assertFailsWith<IllegalStateException> { service.snapshot(ENV) }

        assertEquals("Environment with key $ENV has no model", refusal.message)
    }

    @Test
    fun `a configuring environment yields its uploaded model at revision zero`() {
        val model = cleaningRobot()
        environments.create(ENV, model)

        val snapshot = service.snapshot(ENV)

        assertEquals(
            ModelSnapshot(ENV, EnvironmentPhase.CONFIGURING, 0L, emptyMap(), listOf(rootJson(model))),
            snapshot
        )
    }

    @Test
    fun `the roots are written exactly like objectChanged values`() {
        val model = cleaningRobot()
        environments.create(ENV, model)
        val pushedValues = mutableListOf<String>()
        val modelQueryProcessor = ModelQueryProcessor(model.metamodel!!, model)
        modelQueryProcessor.addChangePublisher(ModelChangePublisher { _, jsonValue -> pushedValues.add(jsonValue) })

        modelQueryProcessor.updateRelations("APB 2101", emptyMap())

        assertEquals(pushedValues, service.snapshot(ENV).roots)
    }

    @Test
    fun `an activated environment is read through its tick gate`() {
        val betweenTicks =
            ModelSnapshot(ENV, EnvironmentPhase.RUNNING, 57L, mapOf("turtle" to listOf("idle")), listOf())
        val environment = FakeObservedEnvironment(EnvironmentPhase.RUNNING, betweenTicks)
        environments.create(ENV, cleaningRobot())
        environments.activate(ENV, environment)

        assertSame(betweenTicks, service.snapshot(ENV))
        assertEquals(listOf(TICK_WAIT_MS), environment.requestedTimeouts)
    }

    @Test
    fun `an environment that reaches no tick boundary within the wait is rejected`() {
        val environment = FakeObservedEnvironment(EnvironmentPhase.RUNNING, null)
        environments.create(ENV, cleaningRobot())
        environments.activate(ENV, environment)

        val refusal = assertFailsWith<IllegalStateException> { service.snapshot(ENV) }

        assertEquals("Environment with key $ENV did not reach a tick boundary within $TICK_WAIT_MS ms", refusal.message)
        assertEquals(listOf(TICK_WAIT_MS), environment.requestedTimeouts)
    }

    @Test
    fun `a model that gets an execution thread while it is written is read again through the gate`() {
        val betweenTicks = ModelSnapshot(ENV, EnvironmentPhase.ACTIVATED, 0L, emptyMap(), listOf())
        val activatedMeanwhile = ActivatedWhileWritten(
            cleaningRobot(),
            FakeObservedEnvironment(EnvironmentPhase.ACTIVATED, betweenTicks)
        )

        val snapshot = ModelSnapshotService(activatedMeanwhile, TICK_WAIT_MS).snapshot(ENV)

        assertSame(betweenTicks, snapshot)
    }

    @Test
    fun `a model with a reference loop is rejected naming the object`() {
        environments.create(ENV, RingExample.parse())

        val refusal = assertFailsWith<IllegalStateException> { service.snapshot(ENV) }

        assertEquals("Reference loop through object 'a' cannot be written as JSON", refusal.message)
    }

    /** Parses the cleaning-robot example. */
    private fun cleaningRobot(): Model = KmodelDSLConverter.parseKmodelString(
        CleaningRobotExample.model,
        KmetaDSLConverter.parseKmetaString(CleaningRobotExample.metamodel)
    )

    /** The JSON of the model's single root, as the engine's change publishers receive it. */
    private fun rootJson(model: Model): String =
        ModelQueryProcessor(model.metamodel!!, model).dataObjectToJson(model.objects.single())

    /**
     * A configuring environment that is activated while its uploaded model is read: it has no execution thread when
     * the service first asks and has one from the moment the model was handed out.
     */
    private class ActivatedWhileWritten(
        private val model: Model,
        private val environment: ObservedEnvironment
    ) : EnvironmentLookup {

        /** Becomes true when the uploaded model was handed out. */
        private var modelHandedOut = false

        /** Every key exists. */
        override fun exists(envKey: String): Boolean = true

        /** Hands out the model, which activates the environment. */
        override fun uploadedModel(envKey: String): Model {
            modelHandedOut = true
            return model
        }

        /** The environment once the model was handed out. */
        override fun observed(envKey: String): ObservedEnvironment? = if (modelHandedOut) environment else null
    }

    private companion object {
        /** The environment of these tests. */
        const val ENV = "env-1761560123456"

        /** The service's wait for a tick boundary. */
        const val TICK_WAIT_MS = 1_500L
    }
}
