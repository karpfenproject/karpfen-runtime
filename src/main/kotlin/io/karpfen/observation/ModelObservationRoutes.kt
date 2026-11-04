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

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The HTTP routes of model observation, added next to the runtime's other routes.
 *
 * Parameters are read in the order `envKey`, then `clientId`, and a missing one is refused with the runtime's text
 * `Missing required parameter: <name>`. Errors are answered by the [errors] responder that the server hands in, so they
 * are answered like on every other route.
 *
 * The service calls block: a snapshot waits up to its tick wait for a tick boundary, and subscribing and unsubscribing
 * take the locks of the session manager. The server runs route handlers on the event loop of their connection, which
 * other connections share, so every service call runs on [blockingCalls] instead, and a hung tick holds up only the
 * requests that wait for it.
 *
 * @property snapshots answers `GET /observatory/model`.
 * @property subscriptions serves `POST /observatory/subscribeModel` and `POST /observatory/unsubscribeModel`.
 * @property errors answers the requests that failed.
 * @property blockingCalls runs the service calls; by default the dispatcher made for blocking calls.
 */
class ModelObservationRoutes(
    private val snapshots: ModelSnapshotService,
    private val subscriptions: ModelSubscriptionService,
    private val errors: ErrorResponder,
    private val blockingCalls: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Adds the routes to the routing of [application]; Ktor reuses the routing that is already installed.
     *
     * `GET /observatory/model?envKey=<envKey>` answers the [ModelSnapshot] as `application/json; charset=UTF-8`.
     * `POST /observatory/subscribeModel?envKey=<envKey>&clientId=<clientId>` and
     * `POST /observatory/unsubscribeModel?envKey=<envKey>&clientId=<clientId>` answer 200 with an empty body.
     */
    fun install(application: Application) {
        application.routing {
            get("/observatory/model") {
                answer(call) {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val snapshot = withContext(blockingCalls) { snapshots.snapshot(envKey) }
                    call.respondText(snapshot.toJson(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                }
            }

            post("/observatory/subscribeModel") {
                answer(call) {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val clientId = call.request.queryParameters["clientId"]
                        ?: throw IllegalArgumentException("Missing required parameter: clientId")
                    withContext(blockingCalls) { subscriptions.subscribe(envKey, clientId) }
                    call.respond(HttpStatusCode.OK, "")
                }
            }

            post("/observatory/unsubscribeModel") {
                answer(call) {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val clientId = call.request.queryParameters["clientId"]
                        ?: throw IllegalArgumentException("Missing required parameter: clientId")
                    withContext(blockingCalls) { subscriptions.unsubscribe(envKey, clientId) }
                    call.respond(HttpStatusCode.OK, "")
                }
            }
        }
    }

    /**
     * Runs [handle] for [call] and answers an exception it throws through [errors]. A cancellation is rethrown: the
     * call has ended, and nobody waits for an answer.
     */
    private suspend fun answer(call: ApplicationCall, handle: suspend () -> Unit) {
        try {
            handle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.respond(call, e)
        }
    }
}
