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

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import dsl.textual.KstatesDSLConverter
import instance.Model
import meta.Metamodel
import states.StateMachine

/**
 * Provides a shared test fixture based on the cleaning robot example.
 */
object TestFixtures {

    val kmetaContent = """
        type "Vector" "A vector in 2D space" {
            prop("x", "number")
            prop("y", "number")
        }

        type "TwoDObject" "A two-dimensional object with a position and diameter" {
            prop("diameter", "number")
            has("position", "Vector")
        }

        type "Obstacle" "circular obstacle in the room" {
            has("boundingBox", "TwoDObject")
        }

        type "Wall" "An obstacle that represents a wall as an infinite line" {
            has("p", "Vector")
            has("u_vec", "Vector")
        }

        type "Robot" "A cleaning robot that can move around the room and log its actions" {
            prop("log", list("string"))

            prop("d_closest_obstacle", "number")
            prop("d_closest_wall", "number")

            has("boundingBox", "TwoDObject")
            has("direction", "Vector")

            knows("obstacles", list("Obstacle"))
            knows("walls", list("Wall"))

            knows("closest_obstacle", "Obstacle")
            knows("closest_wall", "Wall")
        }

        type "Room" "A room that contains a robot and obstacles" {
            has("robot", "Robot")
            has("obstacles", list("Obstacle"))
            has("walls", list("Wall"))
        }
    """.trimIndent()

    val kmodelContent = """
        make object "APB 2101":"Room" {

            has("robot") -> make object "turtle":"Robot" {

                prop("d_closest_obstacle") -> "100.0"
                prop("d_closest_wall") -> "100.0"

                has("boundingBox") -> make object "turtleBoundingBox":"TwoDObject" {
                    prop("diameter") -> "0.2"
                    has("position") -> make object "turtlePosition":"Vector" {
                        prop("x") -> "5.0"
                        prop("y") -> "5.0"
                    }
                }
                has("direction") -> make object "turtleDirection":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "1.0"
                }

                knows("obstacles") -> "chair"
                knows("obstacles") -> "table"

                knows("walls") -> "wall_top"
                knows("walls") -> "wall_right"
                knows("walls") -> "wall_bottom"
                knows("walls") -> "wall_left"

                knows("closest_obstacle") -> "chair"
                knows("closest_wall") -> "wall_top"
            }

            has("obstacles") -> make object "chair":"Obstacle" {
                has("boundingBox") -> make object "chairBoundingBox":"TwoDObject" {
                    prop("diameter") -> "1.0"
                    has("position") -> make object "chairPosition":"Vector" {
                        prop("x") -> "2.0"
                        prop("y") -> "3.0"
                    }
                }
            }
            has("obstacles") -> make object "table":"Obstacle" {
                has("boundingBox") -> make object "tableBoundingBox":"TwoDObject" {
                    prop("diameter") -> "3.0"
                    has("position") -> make object "tablePosition":"Vector" {
                        prop("x") -> "5.0"
                        prop("y") -> "7.0"
                    }
                }
            }

            has("walls") -> make object "wall_top":"Wall" {
                has("p") -> make object "wallTopP":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "10.0"
                }
                has("u_vec") -> make object "wallTopUVec":"Vector" {
                    prop("x") -> "1.0"
                    prop("y") -> "0.0"
                }
            }
            has("walls") -> make object "wall_right":"Wall" {
                has("p") -> make object "wallRightP":"Vector" {
                    prop("x") -> "10.0"
                    prop("y") -> "0.0"
                }
                has("u_vec") -> make object "wallRightUVec":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "1.0"
                }
            }
            has("walls") -> make object "wall_bottom":"Wall" {
                has("p") -> make object "wallBottomP":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "0.0"
                }
                has("u_vec") -> make object "wallBottomUVec":"Vector" {
                    prop("x") -> "1.0"
                    prop("y") -> "0.0"
                }
            }
            has("walls") -> make object "wall_left":"Wall" {
                has("p") -> make object "wallLeftP":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "0.0"
                }
                has("u_vec") -> make object "wallLeftUVec":"Vector" {
                    prop("x") -> "0.0"
                    prop("y") -> "1.0"
                }
            }

        }
    """.trimIndent()

    fun buildMetamodel(): Metamodel {
        return KmetaDSLConverter.parseKmetaString(kmetaContent)
    }

    fun buildModel(metamodel: Metamodel): Model {
        return KmodelDSLConverter.parseKmodelString(kmodelContent, metamodel)
    }

    fun buildStateMachine(kstatesContent: String): StateMachine {
        return KstatesDSLConverter.parseKstatesString(kstatesContent)
    }
}

