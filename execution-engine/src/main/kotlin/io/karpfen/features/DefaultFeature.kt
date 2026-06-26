package io.karpfen.io.karpfen.features

/**
 *  Default implementation of a [Feature]
 */

abstract class DefaultFeature(
    override var explicitlyRequested: Boolean
) : Feature {
    override var markedForDeletion: Boolean = false
    override fun onActivate() {}
    override fun onDeactivate() {}
    override fun onMessage(message: String): String {
        return ""
    }
}