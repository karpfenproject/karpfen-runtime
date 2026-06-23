package io.karpfen.io.karpfen.features

import kotlin.reflect.KClass

/**
 * Interface for Feature Providers
 * Used to instantiate features with varying constructors
 * Properties registryName and registryClass are used by the [FeatureFactory]
 */

interface FeatureProvider {
    val registryName: String
    val registryClass: KClass<out Feature>
    fun createFeature(isUserAdded: Boolean, manager: FeatureManager): Feature
}