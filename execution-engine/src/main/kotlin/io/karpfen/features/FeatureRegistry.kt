package io.karpfen.io.karpfen.features

import java.util.*
import kotlin.reflect.KClass

/**
 * Feature Registry for managing the relations between feature providers, feature names and feature classes
 * Automatically loads [FeatureProvider]s
 * Adds a registry to find providers by feature class
 * Adds another registry to find classes by name
 */

object FeatureRegistry {

    private val classRegistry: MutableMap<KClass<out Feature>, FeatureProvider> = mutableMapOf()

    private val nameToClassRegistry: MutableMap<String, KClass<out Feature>> = mutableMapOf()

    private val classToNameRegistry: MutableMap<KClass<out Feature>, String> = mutableMapOf()

    init {
        val serviceLoader = ServiceLoader.load(FeatureProvider::class.java)
        serviceLoader.iterator().forEach { provider ->
            classRegistry[provider.registryClass] = provider
            nameToClassRegistry[provider.registryName] = provider.registryClass
            classToNameRegistry[provider.registryClass] = provider.registryName
        }
    }

    fun getProviderByClass(featureClass: KClass<out Feature>): FeatureProvider? {
        return classRegistry[featureClass]
    }

    fun getClassByName(featureName: String): KClass<out Feature>? {
        return nameToClassRegistry[featureName]
    }

    fun getNameByClass(featureClass: KClass<out Feature>): String? {
        return classToNameRegistry[featureClass]
    }

    fun getFeatureNames(): Set<String> {
        return nameToClassRegistry.keys
    }
}