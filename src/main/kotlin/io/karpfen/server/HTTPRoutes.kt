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

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing

object HTTPRoutes {
    fun configure(application: Application) {
        application.install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            allowHeader(HttpHeaders.ContentType)
        }
        application.routing {
            post("/createEnvironment") {
                try {
                    val envKey = APIService.createEnvironment()
                    call.respond(HttpStatusCode.OK, envKey)
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            put("/setMetamodel") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val metamodel = call.receiveText()
                    APIService.receiveMetamodel(envKey, metamodel)
                    call.respond(HttpStatusCode.OK, "")
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            put("/setModel") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val model = call.receiveText()
                    APIService.receiveModel(envKey, model)
                    call.respond(HttpStatusCode.OK, "")
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            put("/setStateMachine") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val attachedTo = call.request.queryParameters["attachedTo"]
                        ?: throw IllegalArgumentException("Missing required parameter: attachedTo")
                    val stateMachine = call.receiveText()
                    APIService.receiveStateMachine(envKey, attachedTo, stateMachine)
                    call.respond(HttpStatusCode.OK, "")
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            post("/setTickDelay") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val milliseconds = call.request.queryParameters["milliseconds"]?.toIntOrNull()
                        ?: throw IllegalArgumentException("Missing or invalid required parameter: milliseconds")
                    APIService.updateTickDelay(envKey, milliseconds)
                    call.respond(HttpStatusCode.OK, "")
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            post("/setEventTtl") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val ttlMs = call.request.queryParameters["ttlMs"]?.toLongOrNull()
                        ?: throw IllegalArgumentException("Missing or invalid required parameter: ttlMs")
                    APIService.updateEventTtl(envKey, ttlMs)
                    call.respond(HttpStatusCode.OK, "")
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            post("/registerObjectObserver") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val clientId = call.request.queryParameters["clientId"]
                        ?: throw IllegalArgumentException("Missing required parameter: clientId")
                    val objectId = call.request.queryParameters["objectId"]
                        ?: throw IllegalArgumentException("Missing required parameter: objectId")
                    APIService.addObjectObservation(envKey, clientId, objectId)
                    call.respond(HttpStatusCode.OK, "")
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            post("/registerDomainListener") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val clientId = call.request.queryParameters["clientId"]
                        ?: throw IllegalArgumentException("Missing required parameter: clientId")
                    val domain = call.request.queryParameters["domain"]
                        ?: throw IllegalArgumentException("Missing required parameter: domain")
                    APIService.addDomainListener(envKey, clientId, domain)
                    call.respond(HttpStatusCode.OK, "")
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            post("/registerClientForWebSocket") {
                try {
                    val clientId = call.request.queryParameters["clientId"]
                        ?: throw IllegalArgumentException("Missing required parameter: clientId")
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val accessKey = APIService.registerClientForWebSocket(clientId, envKey)
                    call.respond(HttpStatusCode.OK, accessKey)
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            post("/runEnvironment") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    APIService.runEnvironment(envKey)
                    call.respond(HttpStatusCode.OK, "")
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            post("/startEnvironment") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    APIService.startEnvironment(envKey)
                    call.respond(HttpStatusCode.OK, "")
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            post("/stopEnvironment") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    APIService.stopEnvironment(envKey)
                    call.respond(HttpStatusCode.OK, "")
                } catch (e: Exception) {
                    respondWithError(call, e)
                }
            }

            get("/health") {
                call.respond(mapOf("status" to "ok"))
            }
        }
    }

    private suspend fun respondWithError(call: ApplicationCall, e: Exception) {
        val status = when (e) {
            is IllegalArgumentException -> HttpStatusCode.BadRequest
            is IllegalStateException -> HttpStatusCode.Conflict
            else -> HttpStatusCode.InternalServerError
        }
        call.respond(status, e.message ?: "Unexpected error")
    }
}

