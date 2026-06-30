package io.karpfen.io.karpfen.features

/**
 *  Default implementation of a [Feature]
 */

abstract class DefaultFeature : Feature {
    override fun onActivate() {}
    override fun onDeactivate() {}
    override fun onMessage(message: String): String {
        return ""
    }
}