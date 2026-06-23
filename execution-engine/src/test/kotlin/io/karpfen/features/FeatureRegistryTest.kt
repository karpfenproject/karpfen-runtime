package io.karpfen.features

import io.karpfen.io.karpfen.features.FeatureRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureRegistryTest {

    @Test
    fun testProviderByClassRegistry() {

        assertNotNull(FeatureRegistry.getProviderByClass(objectMap[0]::class))
        assertNotNull(FeatureRegistry.getProviderByClass(objectMap[1]::class))
        assertNotNull(FeatureRegistry.getProviderByClass(objectMap[2]::class))

        assertNull(FeatureRegistry.getProviderByClass(object: DummyFactoryFeature(true) {}::class))

        assertEquals(DummyFeatureProvider0::class, FeatureRegistry.getProviderByClass(objectMap[0]::class)!!::class)
        assertEquals(DummyFeatureProvider1::class, FeatureRegistry.getProviderByClass(objectMap[1]::class)!!::class)
        assertEquals(DummyFeatureProvider2::class, FeatureRegistry.getProviderByClass(objectMap[2]::class)!!::class)

    }

    @Test
    fun testClassByNameRegistry() {
        assertNotNull(FeatureRegistry.getClassByName("dummy0"))
        assertNotNull(FeatureRegistry.getClassByName("dummy1"))
        assertNotNull(FeatureRegistry.getClassByName("dummy2"))

        assertNull(FeatureRegistry.getClassByName("dummy3"))

        assertEquals(objectMap[0]::class, FeatureRegistry.getClassByName("dummy0")!!)
        assertEquals(objectMap[1]::class, FeatureRegistry.getClassByName("dummy1")!!)
        assertEquals(objectMap[2]::class, FeatureRegistry.getClassByName("dummy2")!!)
    }

    @Test
    fun testNameByClassRegistry() {
        assertNotNull(FeatureRegistry.getNameByClass(objectMap[0]::class))
        assertNotNull(FeatureRegistry.getNameByClass(objectMap[1]::class))
        assertNotNull(FeatureRegistry.getNameByClass(objectMap[2]::class))

        assertNull(FeatureRegistry.getNameByClass(object: DummyFactoryFeature(true) {}::class))

        assertEquals("dummy0", FeatureRegistry.getNameByClass(objectMap[0]::class))
        assertEquals("dummy1", FeatureRegistry.getNameByClass(objectMap[1]::class))
        assertEquals("dummy2", FeatureRegistry.getNameByClass(objectMap[2]::class))
    }
}