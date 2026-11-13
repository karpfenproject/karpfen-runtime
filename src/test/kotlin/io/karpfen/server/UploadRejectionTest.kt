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
package io.karpfen.server

import io.karpfen.env.EnvironmentHandler
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the upload routes `PUT /setMetamodel`, `/setModel`, `/setEventDefinitions` and `/setStateMachine` in Ktor's
 * test application, which opens no port: a text with errors is refused with `400` and a body that lists every error
 * with its line and column in the order of the text, a text with a warning only is accepted, a refused upload
 * leaves the environment as it was, and the examples of the runtime are accepted.
 */
class UploadRejectionTest {

    /** The environments the tests created, removed again after each test. */
    private val createdEnvironments = mutableListOf<String>()

    @AfterEach
    fun removeEnvironments() {
        createdEnvironments.forEach { EnvironmentHandler.envs.remove(it) }
    }

    @Test
    fun `a metamodel with two errors is refused with both errors in the order of the text`() = uploadApplication {
        val envKey = newEnvironment()

        val response = client.put("/setMetamodel?envKey=$envKey") { setBody(METAMODEL_WITH_TWO_ERRORS) }

        assertAnswer(
            HttpStatusCode.BadRequest,
            """
            Upload of the metamodel rejected: 2 errors
            2:16: Unknown type 'decimal' of prop 'x'; a prop is of type 'string', 'number', 'boolean' (kmeta.unknown-property-type)
            6:22: Unknown class type 'Place' of has 'position' in type 'Robot' (kmeta.unknown-target-type)
            """.trimIndent(),
            response
        )
    }

    @Test
    fun `a model with two errors is refused with both errors in the order of the text`() = uploadApplication {
        val envKey = newEnvironment()
        APIService.receiveMetamodel(envKey, METAMODEL)

        val response = client.put("/setModel?envKey=$envKey") { setBody(MODEL_WITH_TWO_ERRORS) }

        assertAnswer(
            HttpStatusCode.BadRequest,
            """
            Upload of the model rejected: 2 errors
            2:23: 'fast' is not a number, but prop 'speed' is of type 'number' (kmodel.invalid-number)
            3:11: Class type 'Robot' has no property 'colour' (object 'turtle') (kmodel.unknown-property)
            """.trimIndent(),
            response
        )
    }

    @Test
    fun `event definitions with two errors are refused with both errors in the order of the text`() =
        uploadApplication {
            val envKey = newEnvironment()
            APIService.receiveMetamodel(envKey, METAMODEL)

            val response = client.put("/setEventDefinitions?envKey=$envKey") { setBody(EVENTS_WITH_TWO_ERRORS) }

            assertAnswer(
                HttpStatusCode.BadRequest,
                """
                Upload of the event definitions rejected: 2 errors
                2:21: Unknown type 'fraction' of prop 'factor'; a prop is of type 'string', 'number', 'boolean' (kmeta.unknown-property-type)
                6:22: Unknown class type 'Place' of has 'position' in type 'teleport' (kmeta.unknown-target-type)
                """.trimIndent(),
                response
            )
        }

    @Test
    fun `a state machine with two errors is refused with both errors in the order of the text`() = uploadApplication {
        val envKey = newEnvironment()

        val response = client.put("/setStateMachine?envKey=$envKey&attachedTo=turtle") {
            setBody(STATE_MACHINE_WITH_TWO_ERRORS)
        }

        assertAnswer(
            HttpStatusCode.BadRequest,
            """
            Upload of the state machine for 'turtle' rejected: 2 errors
            5:16: There is already a state named 'idle' in line 3; every state needs a name of its own (kstates.duplicate-state)
            9:31: No state is named 'stop'; check the spelling or define the state (kstates.unknown-state)
            """.trimIndent(),
            response
        )
    }

    @Test
    fun `a model with a warning only is accepted and stored`() = uploadApplication {
        val envKey = newEnvironment()
        APIService.receiveMetamodel(envKey, METAMODEL)

        val response = client.put("/setModel?envKey=$envKey") { setBody(MODEL_WITH_WARNING_ONLY) }

        assertAnswer(HttpStatusCode.OK, "", response)
        val stored = EnvironmentHandler.getEnv(envKey)!!.model!!
        assertEquals(listOf("turtle"), stored.objects.map { it.id })
    }

    @Test
    fun `every example of the runtime is accepted`() = uploadApplication {
        val folders = listOf(File("examples"), File("../examples")).first { it.isDirectory }
            .listFiles { file -> file.isDirectory }!!.sortedBy { it.name }
        assertTrue(folders.isNotEmpty(), "No example folders found")

        folders.forEach { folder ->
            val files = folder.listFiles()!!.filter { it.isFile }
            val domain = files.single { it.extension == "kmeta" && it.name != "EVENTS.kmeta" }
            val envKey = newEnvironment()
            val answers = listOf(
                client.put("/setMetamodel?envKey=$envKey") { setBody(domain.readText()) },
                client.put("/setEventDefinitions?envKey=$envKey") { setBody(File(folder, "EVENTS.kmeta").readText()) },
                client.put("/setModel?envKey=$envKey") { setBody(files.single { it.extension == "kmodel" }.readText()) },
                client.put("/setStateMachine?envKey=$envKey&attachedTo=example") {
                    setBody(files.single { it.extension == "kstates" }.readText())
                }
            )
            answers.forEach { assertAnswer(HttpStatusCode.OK, "", it) }
        }
    }

    @Test
    fun `refused uploads leave the environment as it was`() = uploadApplication {
        val envKey = newEnvironment()
        APIService.receiveMetamodel(envKey, METAMODEL)
        APIService.receiveModel(envKey, MODEL)
        APIService.receiveEventDefinitions(envKey, EVENTS)
        APIService.receiveStateMachine(envKey, "turtle", STATE_MACHINE)
        val env = EnvironmentHandler.getEnv(envKey)!!
        val metamodel = env.metamodel
        val model = env.model
        val eventMetamodel = env.eventMetamodel
        val stateMachine = env.stateMachines["turtle"]

        val answers = listOf(
            client.put("/setMetamodel?envKey=$envKey") { setBody(METAMODEL_WITH_TWO_ERRORS) },
            client.put("/setModel?envKey=$envKey") { setBody(MODEL_WITH_TWO_ERRORS) },
            client.put("/setEventDefinitions?envKey=$envKey") { setBody(EVENTS_WITH_TWO_ERRORS) },
            client.put("/setStateMachine?envKey=$envKey&attachedTo=turtle") { setBody(STATE_MACHINE_WITH_TWO_ERRORS) },
            client.put("/setStateMachine?envKey=$envKey&attachedTo=lamp") { setBody(STATE_MACHINE_WITH_TWO_ERRORS) }
        )

        assertTrue(answers.all { it.status == HttpStatusCode.BadRequest }, "Statuses: ${answers.map { it.status }}")
        assertSame(metamodel, env.metamodel)
        assertSame(model, env.model)
        assertSame(eventMetamodel, env.eventMetamodel)
        assertEquals(EVENTS, env.eventDefinitionsSource)
        assertSame(stateMachine, env.stateMachines["turtle"])
        assertEquals(STATE_MACHINE, env.stateMachineSources["turtle"])
        assertEquals(setOf("turtle"), env.stateMachines.keys)
        assertNull(env.stateMachineSources["lamp"])
    }

    /** Runs [test] in a test application with the runtime's HTTP routes. */
    private fun uploadApplication(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { HTTPRoutes.configure(this) }
        test()
    }

    /** Creates an environment that is removed again after the test and returns its key. */
    private fun newEnvironment(): String = APIService.createEnvironment().also { createdEnvironments.add(it) }

    /** Asserts that [response] has [status] and exactly [body]. */
    private suspend fun assertAnswer(status: HttpStatusCode, body: String, response: HttpResponse) {
        assertEquals(status, response.status)
        assertEquals(body, response.bodyAsText())
    }

    private companion object {

        /** A domain metamodel without problems, whose root type is `Robot`. */
        val METAMODEL = """
            type "Vector" "A vector in 2D space" {
                prop("x", "number")
                prop("y", "number")
            }

            type "Point" "A point in 2D space" {
                prop("x", "number")
                prop("y", "number")
            }

            type "Robot" "A robot" {
                prop("speed", "number")
                has("position", "Vector")
            }
        """.trimIndent()

        /** A metamodel with a property of an unknown type and a relation to an unknown type. */
        val METAMODEL_WITH_TWO_ERRORS = """
            type "Vector" "A vector" {
                prop("x", "decimal")
            }

            type "Robot" "A robot" {
                has("position", "Place")
            }
        """.trimIndent()

        /** A model of [METAMODEL] without problems. */
        val MODEL = """
            make object "turtle":"Robot" {
                prop("speed") -> "1.0"
            }
        """.trimIndent()

        /** A model of [METAMODEL] with a speed that is no number and an unknown property. */
        val MODEL_WITH_TWO_ERRORS = """
            make object "turtle":"Robot" {
                prop("speed") -> "fast"
                prop("colour") -> "red"
            }
        """.trimIndent()

        /** A model of [METAMODEL] whose only problem is an embedded `Point` where the relation declares a `Vector`. */
        val MODEL_WITH_WARNING_ONLY = """
            make object "turtle":"Robot" {
                prop("speed") -> "1.0"
                has("position") -> make object "spot":"Point" {
                    prop("x") -> "1.0"
                    prop("y") -> "2.0"
                }
            }
        """.trimIndent()

        /** Event definitions without problems, one of them embedding the domain type `Vector`. */
        val EVENTS = """
            type "go" "Starts the robot" {
            }

            type "teleport" "Moves the robot" {
                has("position", "Vector")
            }
        """.trimIndent()

        /** Event definitions with a property of an unknown type and a relation to a type no metamodel defines. */
        val EVENTS_WITH_TWO_ERRORS = """
            type "setSpeed" "Changes the speed" {
                prop("factor", "fraction")
            }

            type "teleport" "Moves the robot" {
                has("position", "Place")
            }
        """.trimIndent()

        /** A state machine without problems. */
        val STATE_MACHINE = """
            STATEMACHINE ATTACHED TO "Robot" {
                STATES {
                    INITIAL STATE "idle" {
                    }
                    STATE "step" {
                    }
                }
                TRANSITIONS {
                    TRANSITION "idle" -> "step" {
                        CONDITION {
                            EVENT("public", "go")
                        }
                    }
                }
            }
        """.trimIndent()

        /** A state machine with a state defined twice and a transition to a state that does not exist. */
        val STATE_MACHINE_WITH_TWO_ERRORS = """
            STATEMACHINE ATTACHED TO "Robot" {
                STATES {
                    INITIAL STATE "idle" {
                    }
                    STATE "idle" {
                    }
                }
                TRANSITIONS {
                    TRANSITION "idle" -> "stop" {
                        CONDITION {
                            EVENT("public", "go")
                        }
                    }
                }
            }
        """.trimIndent()
    }
}
