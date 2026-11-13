package io.karpfen.io.karpfen.features

import kotlin.reflect.KClass

/**
 * Interface for Feature Providers
 *
 * Used to instantiate features with varying constructors
 *
 * Properties registryName and registryClass are used by the [FeatureFactory]
 */

interface FeatureProvider {

    val registryName: String

    val registryClass: KClass<out Feature>

    //Dependencies on other features of the feature the provider provides
    //Either for usage in constructor or general functionality
    val featureDependencies: Set<KClass<out Feature>>

    fun createFeature(manager: FeatureManager): Feature
}