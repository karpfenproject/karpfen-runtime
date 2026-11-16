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
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.coroutines.cancellation.CancellationException

/**
 * The route `GET /observatory/metrics?envKey=<envKey>`, added next to the other observation routes. It answers the
 * [RunMetricsAnswer] of the environment as `application/json`. A missing `envKey` is refused with the runtime's text
 * `Missing required parameter: envKey`; every failure is answered by [errors], like on every other route.
 *
 * @property metrics reads the numbers of an environment.
 * @property errors answers the requests that failed.
 */
class RunMetricsRoutes(
    private val metrics: RunMetricsService,
    private val errors: ErrorResponder
) {

    /** Adds the route to the routing of [application]; Ktor reuses the routing that is already installed. */
    fun install(application: Application) {
        application.routing {
            get("/observatory/metrics") {
                try {
                    val envKey = call.request.queryParameters["envKey"]
                        ?: throw IllegalArgumentException("Missing required parameter: envKey")
                    call.respondText(metrics.metrics(envKey).toJson(), ContentType.Application.Json)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors.respond(call, e)
                }
            }
        }
    }
}
