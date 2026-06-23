package io.karpfen.io.karpfen.features

import kotlin.reflect.KClass

/**
 * Factory for producing [Feature]s
 */

object FeatureFactory {

    //Create feature by class
    fun createFeature(featureClass: KClass<out Feature>, isUserAdded: Boolean, manager: FeatureManager): Feature {
        val featureProvider = FeatureRegistry.getProviderByClass(featureClass)

        check(featureProvider !== null) {"${FeatureRegistry.getNameByClass(featureClass)} has no corresponding feature provider"}

        return featureProvider.createFeature(isUserAdded, manager)
    }
}