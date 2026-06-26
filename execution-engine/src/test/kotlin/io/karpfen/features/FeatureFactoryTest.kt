package io.karpfen.features

import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureFactory
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureRegistry
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureFactoryTest {

    val manager = FeatureManager()

    @Test
    fun testFeatureCreationClass() {

        val activeFeaturesField = manager::class.java.getDeclaredField("activeFeatureRegistry")
        activeFeaturesField.setAccessible(true)
        @Suppress("UNCHECKED_CAST")
        val activeFeaturesRegistry = activeFeaturesField.get(manager) as ConcurrentHashMap<KClass<out Feature>, Feature>

        var explicitlyRequested = false

        for (featureClass in setOf(FeatA::class, FeatB::class, FeatC::class, FeatD::class, FeatE::class, FeatF::class, FeatG::class, FeatH::class)) {
            val feature = FeatureFactory.createFeature(featureClass, explicitlyRequested, manager)
            assertEquals(featureClass, feature::class)
            assertEquals(explicitlyRequested, feature.explicitlyRequested)
            explicitlyRequested = !explicitlyRequested
            activeFeaturesRegistry[featureClass] = feature
        }
    }

    @Test
    fun testMissingRegistryEntries() {

        val featureClass = object: FeatA(true) {}::class

        val exception = assertThrows<IllegalStateException> {
            FeatureFactory.createFeature(featureClass, false, manager)
        }

        assertEquals("${FeatureRegistry.getNameByClass(featureClass)} has no corresponding feature provider", exception.message)

    }
}