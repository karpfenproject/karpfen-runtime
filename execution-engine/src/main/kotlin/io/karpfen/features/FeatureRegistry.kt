package io.karpfen.io.karpfen.features

import java.util.*
import kotlin.reflect.KClass

/**
 * Feature Registry for managing the relations between feature providers, feature names and feature classes
 *
 * Automatically loads [FeatureProvider]s as service
 *
 * Adds registries to find providers by feature class and name, and to find feature name by class
 *
 * Keeps track of the global feature dependency graph, simultaneously preventing cyclic dependencies
 */

object FeatureRegistry {

    private val classToProviderRegistry: MutableMap<KClass<out Feature>, FeatureProvider> = hashMapOf()

    private val nameToClassRegistry: MutableMap<String, KClass<out Feature>> = hashMapOf()

    private val classToNameRegistry: MutableMap<KClass<out Feature>, String> = hashMapOf()

    private val featureDependencies: MutableMap<KClass<out Feature>, Set<KClass<out Feature>>> = hashMapOf()

    init {
        initialize()
    }

    private fun initialize() {
        val serviceLoader = ServiceLoader.load(FeatureProvider::class.java)
        serviceLoader.iterator().forEach { provider ->
            classToProviderRegistry[provider.registryClass] = provider
            nameToClassRegistry[provider.registryName] = provider.registryClass
            classToNameRegistry[provider.registryClass] = provider.registryName
            featureDependencies[provider.registryClass] = provider.featureDependencies
        }
    }

    fun getProviderByClass(featureClass: KClass<out Feature>): FeatureProvider? {
        return classToProviderRegistry[featureClass]
    }

    fun getNameByClass(featureClass: KClass<out Feature>): String? {
        return classToNameRegistry[featureClass] ?: featureClass.simpleName
    }

    fun getClassByName(featureName: String): KClass<out Feature>? {
        return nameToClassRegistry[featureName]
    }

    fun getFeatureNames(): Set<String> {
        return nameToClassRegistry.keys
    }

    fun getFeatureActivationOrder(featureClass: KClass<out Feature>): ArrayList<KClass<out Feature>> {
        val order = ArrayList<KClass<out Feature>>()
        val visited = HashSet<KClass<out Feature>>()
        //Used for detecting cyclic dependencies
        val visiting = HashSet<KClass<out Feature>>()

        fun depthFirstSearch(currentClass: KClass<out Feature>) {

            if (visiting.contains(currentClass)) {
                throw IllegalStateException("Cyclic dependency detected at: ${getNameByClass(currentClass)}")
            }

            if (visited.contains(currentClass)) return

            visiting.add(currentClass)

            val dependencies = featureDependencies[currentClass] ?: emptySet()
            for (dependency in dependencies) {
                depthFirstSearch(dependency)
            }

            visiting.remove(currentClass)
            visited.add(currentClass)
            order.add(currentClass)
        }

        depthFirstSearch(featureClass)
        return order
    }
}