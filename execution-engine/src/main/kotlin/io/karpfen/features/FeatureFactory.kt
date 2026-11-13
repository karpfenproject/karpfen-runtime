package io.karpfen.io.karpfen.features

import kotlin.reflect.KClass

/**
 * Factory for producing [Feature]s
 */

object FeatureFactory {

    fun createFeature(featureClass: KClass<out Feature>, manager: FeatureManager): Feature {
        val featureProvider = FeatureRegistry.getProviderByClass(featureClass)

        check(featureProvider != null) {"${FeatureRegistry.getNameByClass(featureClass)} has no corresponding feature provider"}

        return featureProvider.createFeature(manager)
    }
}