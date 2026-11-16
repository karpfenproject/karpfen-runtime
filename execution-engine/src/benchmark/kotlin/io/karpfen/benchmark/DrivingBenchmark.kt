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
package io.karpfen.benchmark

import io.karpfen.io.karpfen.messages.Event
import kotlinx.benchmark.Benchmark
import org.openjdk.jmh.annotations.OperationsPerInvocation

/**
 * The reference point without Python: the state machine `DrivingModel` of a car that speeds up, waits at a traffic
 * light with a split into the light and a pedestrian, joins again and drives on, with events sent from outside at fixed
 * ticks. Its two macros and two conditions are arithmetic and comparisons, which the runtime computes itself. The score
 * is the time of one tick.
 */
open class DrivingBenchmark : EngineBenchmark() {

    override fun scenario() = Scenario(METAMODEL, MODEL, mapOf("drivingModel" to DRIVING))

    /** Sends a `traffic light spotted` every 30 ticks and two irrelevant events every 11 and 7 ticks. */
    override fun beforeTick(tick: Long, driver: TickDriver) {
        val next = tick + 1
        if (next % 30L == 5L) send(Event("domain", "traffic light spotted", ttlMs = 1))
        if (next % 11L == 0L) send(Event("domain", "irrelevant", ttlMs = 3))
        if (next % 7L == 0L) send(Event("domain", "irrelevant", ttlMs = 5))
    }

    @Benchmark
    @OperationsPerInvocation(TICKS)
    fun tick() = ticks(TICKS)

    private companion object {
        const val TICKS = 100

        val METAMODEL = """
            type "DrivingModel" "advanced state machine for benchmarking" {
                prop("driving", "boolean")
                prop("speed", "number")
            }
        """

        val MODEL = """
            make object "drivingModel":"DrivingModel" {
                prop("driving") -> "true"
                prop("speed") -> "0"
            }
        """

        val DRIVING = """
            STATEMACHINE ATTACHED TO "DrivingModel" {
                STATES {
                    STATE "driving" {
                        ENTRY { SET("driving", "true") }
                        INITIAL STATE "accelerate" {
                            DO { SET("speed", MACRO("accelerate", "speed")) }
                        }
                        STATE "isMaxSpeed" { }
                    }
                    STATE "slowing down" {
                        STATE "slow down" {
                            DO { SET("speed", MACRO("brake", "speed")) }
                        }
                        STATE "isStopped" { }
                    }
                    STATE "waiting at traffic light" {
                        ENTRY {
                            SET("driving", "false")
                            SET("speed", "0")
                        }
                        STATE "traffic light state" {
                            STATE "turningGreen" {
                                STATE "turningGreenIsRed" { }
                                STATE "turningGreenIsYellow" { }
                            }
                            STATE "turningRed" {
                                STATE "turningRedIsGreen" { }
                                STATE "turningRedIsYellow" { }
                            }
                        }
                        STATE "pedestrian state" {
                            STATE "not present" { }
                            STATE "about to cross" { }
                            STATE "crossing" { }
                        }
                    }
                }
                TRANSITIONS {
                    TRANSITION "isMaxSpeed" -> "isStopped" { CONDITION { EVENT("domain", "traffic light spotted") } }
                    TRANSITION "accelerate" -> "isStopped" { CONDITION { EVENT("domain", "traffic light spotted") } }
                    TRANSITION "accelerate" -> "isMaxSpeed" { }
                    TRANSITION "isMaxSpeed" -> "accelerate" { CONDITION { EVAL { return $(speed) < 50 } } }
                    TRANSITION "slow down" -> "isStopped" { }
                    TRANSITION "isStopped" -> "slow down" { CONDITION { EVAL { return $(speed) > 0 } } }
                    SPLIT "isStopped" -> "turningGreenIsRed", "not present" { }
                    JOIN "turningRedIsGreen", "not present" -> "accelerate" { }
                    TRANSITION "turningGreenIsRed" -> "turningGreenIsYellow" { }
                    TRANSITION "turningGreenIsYellow" -> "turningRedIsGreen" { }
                    TRANSITION "turningRedIsGreen" -> "turningRedIsYellow" { }
                    TRANSITION "turningRedIsYellow" -> "turningGreenIsRed" { }
                    TRANSITION "not present" -> "about to cross" { }
                    TRANSITION "about to cross" -> "crossing" { }
                    TRANSITION "crossing" -> "not present" { }
                }
                MACROS {
                    MACRO "accelerate" {
                        TAKES("currentSpeed", "number")
                        RETURNS("number")
                        DEFINITION { EVAL { return ($(currentSpeed) + 1) } }
                    }
                    MACRO "brake" {
                        TAKES("currentSpeed", "number")
                        RETURNS("number")
                        DEFINITION { EVAL { return ($(currentSpeed) - 5) } }
                    }
                }
            }
        """
    }
}
