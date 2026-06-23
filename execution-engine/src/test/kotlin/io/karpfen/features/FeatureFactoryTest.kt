package io.karpfen.features

import io.karpfen.io.karpfen.features.FeatureFactory
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureRegistry
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureFactoryTest {

    val manager = FeatureManager()
    
    @Test
    fun testFeatureCreationClass() {
        
        assertEquals(objectMap[0], FeatureFactory.createFeature(objectMap[0]::class, true, manager))
        assertEquals(objectMap[1], FeatureFactory.createFeature(objectMap[1]::class, true, manager))
        assertEquals(objectMap[2], FeatureFactory.createFeature(objectMap[2]::class, true, manager))
        
    }
    
    @Test 
    fun testMissingRegistryEntries() {

        val featureClass = object: DummyFactoryFeature(true) {}::class

        val exception = assertThrows<IllegalStateException> {
            FeatureFactory.createFeature(featureClass, false, manager)
        }

        assertEquals("${FeatureRegistry.getNameByClass(featureClass)} has no corresponding feature provider", exception.message)

    }
}