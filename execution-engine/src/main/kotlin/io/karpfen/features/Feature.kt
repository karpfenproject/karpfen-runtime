package io.karpfen.io.karpfen.features

/**
 *  Interface for Features
 */

interface Feature {

    fun onActivate()

    fun onDeactivate()

    fun onMessage(message: String): String
}