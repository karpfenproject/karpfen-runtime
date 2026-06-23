package io.karpfen.io.karpfen.features

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/**
 * Feature Manager for managing [Feature]s
 * Inspired by Service Locators, includes custom dependency injection
 */

class FeatureManager {

    private val _activeFeatureRegistry: ConcurrentHashMap<KClass<out Feature>, Feature> = ConcurrentHashMap()

    val activeFeatureRegistry get() = _activeFeatureRegistry.toMap()

    //Atomic Boolean for preventing multiple threads to change dependency tree simultaneously
    private val isUpdatingFeatures: AtomicBoolean = AtomicBoolean(false)

    //Requests an update to the dependency tree
    fun requestFeatureUpdate(): Boolean {
        return isUpdatingFeatures.compareAndSet(false, true)
    }

    fun unlockFeatureUpdate() {
        isUpdatingFeatures.lazySet(false)
    }

    fun addFeature(feature: Feature): Boolean {
        if (_activeFeatureRegistry.containsKey(feature::class)) {
            //Optionally overwrite isUserAdded in present feature
            if (feature.isUserAdded) {
                _activeFeatureRegistry[feature::class]!!.isUserAdded = true
            }
            return false
        }
        for (featureClass in feature.dependencies.toList()) {
            if (_activeFeatureRegistry.containsKey(featureClass)) {
                //Update existing dependencies
                _activeFeatureRegistry[featureClass]!!.addDependant(feature::class)
            } else {
                //Or create new dependency
                val dependency = FeatureFactory.createFeature(featureClass, false, this)
                dependency.addDependant(feature::class)
                addFeature(dependency)
            }
        }
        _activeFeatureRegistry[feature::class] = feature
        feature.onActivate()
        println("${FeatureRegistry.getNameByClass(feature::class)} has been activated")
        return true
    }

    fun removeFeature(feature: Feature): Boolean {
        if (!_activeFeatureRegistry.containsKey(feature::class) || !feature.requestDeletion()) {
            return false
        }
        //Remove Dependants
        for (featureClass in feature.dependants.toList()) {
            _activeFeatureRegistry[featureClass]?.let {
                removeFeature(it)
            }
        }
        _activeFeatureRegistry.remove(feature::class)
        feature.onDeactivate()
        println("${FeatureRegistry.getNameByClass(feature::class)} has been deactivated")
        //Remove dependant entry in dependencies, optionally clean dependency if they do not have any dependants and are not added by the user
        for (featureClass in feature.dependencies.toList()) {
            _activeFeatureRegistry[featureClass]?.let {
                it.removeDependant(feature::class)
                if (it.safeToCleanUp()) removeFeature(it)
            }
        }
        feature.unlockDeletion()
        return true
    }

    fun getFeature(featureClass: KClass<out Feature>): Feature? {
        return _activeFeatureRegistry[featureClass]
    }

    fun getActiveFeaturesClasses(): Set<KClass<out Feature>> {
        return _activeFeatureRegistry.keys
    }

    inline fun <reified T : Feature> executeIfPresent(action: (T) -> Unit) {
        val feature = activeFeatureRegistry[T::class] as? T
        if (feature != null) {
            action(feature)
        }
    }

    fun onMessage(featureClass: KClass<out Feature>, message: String) {
        val feature = _activeFeatureRegistry[featureClass]
            ?: throw IllegalArgumentException("${FeatureRegistry.getNameByClass(featureClass)} has not been activated")
        feature.onMessage(message)
    }
}