package io.karpfen.io.karpfen.features

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/**
 * Feature Manager for managing [Feature]s
 *
 * Inspired by Service Locators, includes custom dependency injection and local dependants graph
 */

class FeatureManager {

    private val activeFeatureRegistry = ConcurrentHashMap<KClass<out Feature>, Feature>()

    private val localDependantsRegistry = mutableMapOf<KClass<out Feature>, MutableSet<KClass<out Feature>>>()

    private val explicitlyRequestedFeatures = mutableSetOf<KClass<out Feature>>()

    //Atomic Boolean for preventing multiple threads to change dependency tree simultaneously
    private val isUpdatingFeatures: AtomicBoolean = AtomicBoolean(false)

    //Requests an update to the dependency tree
    private fun requestFeatureUpdate(): Boolean {
        return isUpdatingFeatures.compareAndSet(false, true)
    }

    private fun unlockFeatureUpdate() {
        isUpdatingFeatures.lazySet(false)
    }

    //Explicitly request feature activation
    //Returns true if feature has been activated, false if already present
    fun requestFeatureActivation(featureClass: KClass<out Feature>): Boolean {
        if (!requestFeatureUpdate()) {
            throw IllegalStateException("FeatureManager is already updating features")
        }
        try {
            explicitlyRequestedFeatures.add(featureClass)
            val feature = activeFeatureRegistry[featureClass]
            if (feature != null) {
                return false
            }
            activateFeature(featureClass)
            return true
        } finally {
            unlockFeatureUpdate()
        }
    }

    private fun activateFeature(featureClass: KClass<out Feature>) {
        val activationOrder = FeatureRegistry.getFeatureActivationOrder(featureClass)

        for (clazz in activationOrder) {
            if (!activeFeatureRegistry.containsKey(clazz)) {

                val feature = FeatureFactory.createFeature(clazz, this)

                activeFeatureRegistry[clazz] = feature

                feature.onActivate()

                println("${FeatureRegistry.getNameByClass(clazz)} has been activated")

                FeatureRegistry.getProviderByClass(clazz)?.featureDependencies?.forEach { dependency ->
                    localDependantsRegistry.getOrPut(dependency) { mutableSetOf() }.add(clazz)
                }
            }
        }
    }

    fun requestFeatureDeactivation(featureClass: KClass<out Feature>): Boolean {
        if (!requestFeatureUpdate()) {
            throw IllegalStateException("FeatureManager is already updating features")
        }
        try {
            if (!activeFeatureRegistry.containsKey(featureClass)) {
                return false
            }
            deactivateFeature(featureClass)
            return true
        } finally {
            unlockFeatureUpdate()
        }
    }

    //Explicitly request feature deactivation
    //Returns true if feature has been deactivated, false if not present
    private fun deactivateFeature(featureClass: KClass<out Feature>) {

        val featuresMarkedForDeletion = mutableSetOf<KClass<out Feature>>()

        fun safeToCleanUp(featureClass: KClass<out Feature>): Boolean {
            activeFeatureRegistry[featureClass] ?: return false
            if (featuresMarkedForDeletion.contains(featureClass)) return false
            val dependants = localDependantsRegistry[featureClass] ?: emptySet()
            //Feature can be safely cleaned up if it is not explicitly requested by user and has no dependants
            return !explicitlyRequestedFeatures.contains(featureClass) && dependants.isEmpty()
        }

        fun recursiveDeletion(featureClass: KClass<out Feature>) {
            //Mark feature, so it is not deactivated recursively twice
            if (featuresMarkedForDeletion.contains(featureClass)) return

            featuresMarkedForDeletion.add(featureClass)

            val feature = activeFeatureRegistry[featureClass]!!

            //Remove Dependants
            val dependants = localDependantsRegistry[featureClass]?.toSet() ?: emptySet()
            for (dependant in dependants) {
                activeFeatureRegistry[dependant]?.let {
                    deactivateFeature(dependant)
                }
            }
            explicitlyRequestedFeatures.remove(featureClass)
            localDependantsRegistry.remove(featureClass)
            activeFeatureRegistry.remove(featureClass)
            feature.onDeactivate()
            println("${FeatureRegistry.getNameByClass(featureClass)} has been deactivated")

            FeatureRegistry.getProviderByClass(featureClass)?.featureDependencies?.forEach { dependency ->
                localDependantsRegistry[dependency]?.remove(featureClass)
                if (safeToCleanUp(dependency)) deactivateFeature(dependency)
            }
        }

        recursiveDeletion(featureClass)
    }

    fun getActiveFeature(featureClass: KClass<out Feature>): Feature? {
        return activeFeatureRegistry[featureClass]
    }

    inline fun <reified T: Feature> getActiveFeatureAsClass(): T {
        val feature = getActiveFeature(T::class) as T?
        return checkNotNull(feature) {
            "Feature ${FeatureRegistry.getNameByClass(T::class)} has not been activated"
        }
    }

    fun getActiveFeaturesClasses(): Set<KClass<out Feature>> {
        return activeFeatureRegistry.keys
    }

    inline fun <reified T : Feature> executeIfPresent(action: (T) -> Unit) {
        val feature = getActiveFeature(T::class) as? T
        if (feature != null) {
            action(feature)
        }
    }

    fun onMessage(featureClass: KClass<out Feature>, message: String): String? {
        val feature = activeFeatureRegistry[featureClass] ?: return null
        return feature.onMessage(message)
    }
}