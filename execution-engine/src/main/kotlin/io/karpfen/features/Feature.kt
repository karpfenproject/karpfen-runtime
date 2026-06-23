package io.karpfen.io.karpfen.features

import kotlin.reflect.KClass

/**
 *  Interface for Features
 */

interface Feature {

    //Features required by this Feature
    val dependencies: Set<KClass<out Feature>>

    //Features that require this Feature
    val dependants: MutableSet<KClass<out Feature>>

    var isUserAdded: Boolean

    var markedForDeletion: Boolean

    fun addDependant(featureClass: KClass<out Feature>) {
        dependants.add(featureClass)
    }

    fun removeDependant(featureClass: KClass<out Feature>) {
        dependants.remove(featureClass)
    }

    fun requestDeletion(): Boolean {
        if (markedForDeletion) {
            return false
        }
        markedForDeletion = true
        return true
    }

    fun unlockDeletion() {
        markedForDeletion = false
    }

    fun safeToCleanUp(): Boolean {
        return dependants.isEmpty() && !isUserAdded
    }

    fun onActivate()

    fun onDeactivate()

    fun onMessage(message: String)
}