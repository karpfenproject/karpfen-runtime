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

import io.karpfen.server.HTTPRoutes
import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * The HTTP routes of model observation, added next to the runtime's other routes.
 *
 * Parameters are read in the order `envKey`, then `clientId`, and a missing one is refused with the runtime's text
 * `Missing required parameter: <name>`. Errors are answered by [HTTPRoutes.respondWithError] like on every other
 * route.
 *
 * @property snapshots answers `GET /observatory/model`.
 */
class ModelObservationRoutes(
    private val snapshots: ModelSnapshotService
) {

    /**
     * Adds the routes to the routing of [application]; Ktor reuses the routing that is already installed.
     *
     * `GET /observatory/model?envKey=<envKey>` answers the [ModelSnapshot] as `application/json; charset=UTF-8`.
     */
    fun install(application: Application) {
        application.routing {
            get("/observatory/model") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    val snapshot = snapshots.snapshot(envKey)
                    call.respondText(snapshot.toJson(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                } catch (e: Exception) {
                    HTTPRoutes.respondWithError(call, e)
                }
            }
        }
    }
}
