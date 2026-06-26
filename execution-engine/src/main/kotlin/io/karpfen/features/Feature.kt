package io.karpfen.io.karpfen.features

/**
 *  Interface for Features
 */

interface Feature {

    var explicitlyRequested: Boolean

    var markedForDeletion: Boolean

    fun onActivate()

    fun onDeactivate()

    fun onMessage(message: String): String
}