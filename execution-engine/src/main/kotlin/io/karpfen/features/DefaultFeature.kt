package io.karpfen.io.karpfen.features

import kotlin.reflect.KClass

/**
 *  Default implementation of a [Feature]
 */

abstract class DefaultFeature(
    override var isUserAdded: Boolean
) : Feature {
    override val dependencies: Set<KClass<out Feature>> = emptySet()
    override val dependants: MutableSet<KClass<out Feature>> = mutableSetOf()
    override var markedForDeletion: Boolean = false
    override fun onActivate() {}
    override fun onDeactivate() {}
    override fun onMessage(message: String) {}
}