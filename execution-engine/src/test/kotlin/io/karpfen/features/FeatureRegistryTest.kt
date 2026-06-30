package io.karpfen.features

import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureRegistryTest {

    @AfterEach
    fun reset() {
        val initializeMethod = FeatureRegistry::class.java.getDeclaredMethod("initialize")
        initializeMethod.setAccessible(true)
        initializeMethod.invoke(FeatureRegistry)
    }

    @Test
    fun testProviderByClass() {
        assertNotNull(FeatureRegistry.getProviderByClass(FeatA::class))
        assertNotNull(FeatureRegistry.getProviderByClass(FeatB::class))
        assertNotNull(FeatureRegistry.getProviderByClass(FeatC::class))
        assertNotNull(FeatureRegistry.getProviderByClass(FeatD::class))
        assertNotNull(FeatureRegistry.getProviderByClass(FeatE::class))
        assertNotNull(FeatureRegistry.getProviderByClass(FeatF::class))
        assertNotNull(FeatureRegistry.getProviderByClass(FeatG::class))
        assertNotNull(FeatureRegistry.getProviderByClass(FeatH::class))

        assertNull(FeatureRegistry.getProviderByClass(object : FeatA() {}::class))

        assertEquals(FeatAProvider::class, FeatureRegistry.getProviderByClass(FeatA::class)!!::class)
        assertEquals(FeatBProvider::class, FeatureRegistry.getProviderByClass(FeatB::class)!!::class)
        assertEquals(FeatCProvider::class, FeatureRegistry.getProviderByClass(FeatC::class)!!::class)
        assertEquals(FeatDProvider::class, FeatureRegistry.getProviderByClass(FeatD::class)!!::class)
        assertEquals(FeatEProvider::class, FeatureRegistry.getProviderByClass(FeatE::class)!!::class)
        assertEquals(FeatFProvider::class, FeatureRegistry.getProviderByClass(FeatF::class)!!::class)
        assertEquals(FeatGProvider::class, FeatureRegistry.getProviderByClass(FeatG::class)!!::class)
        assertEquals(FeatHProvider::class, FeatureRegistry.getProviderByClass(FeatH::class)!!::class)

    }

    @Test
    fun testNameByClass() {
        assertNotNull(FeatureRegistry.getNameByClass(FeatA::class))
        assertNotNull(FeatureRegistry.getNameByClass(FeatB::class))
        assertNotNull(FeatureRegistry.getNameByClass(FeatC::class))
        assertNotNull(FeatureRegistry.getNameByClass(FeatD::class))
        assertNotNull(FeatureRegistry.getNameByClass(FeatE::class))
        assertNotNull(FeatureRegistry.getNameByClass(FeatF::class))
        assertNotNull(FeatureRegistry.getNameByClass(FeatG::class))
        assertNotNull(FeatureRegistry.getNameByClass(FeatH::class))

        assertNull(FeatureRegistry.getNameByClass(object : FeatA() {}::class))

        assertEquals("A", FeatureRegistry.getNameByClass(FeatA::class))
        assertEquals("B", FeatureRegistry.getNameByClass(FeatB::class))
        assertEquals("C", FeatureRegistry.getNameByClass(FeatC::class))
        assertEquals("D", FeatureRegistry.getNameByClass(FeatD::class))
        assertEquals("E", FeatureRegistry.getNameByClass(FeatE::class))
        assertEquals("F", FeatureRegistry.getNameByClass(FeatF::class))
        assertEquals("G", FeatureRegistry.getNameByClass(FeatG::class))
        assertEquals("H", FeatureRegistry.getNameByClass(FeatH::class))
    }

    @Test
    fun testClassByName() {
        assertNotNull(FeatureRegistry.getClassByName("A"))
        assertNotNull(FeatureRegistry.getClassByName("B"))
        assertNotNull(FeatureRegistry.getClassByName("C"))
        assertNotNull(FeatureRegistry.getClassByName("D"))
        assertNotNull(FeatureRegistry.getClassByName("E"))
        assertNotNull(FeatureRegistry.getClassByName("F"))
        assertNotNull(FeatureRegistry.getClassByName("G"))
        assertNotNull(FeatureRegistry.getClassByName("H"))

        assertNull(FeatureRegistry.getClassByName("Z"))

        assertEquals(FeatA::class, FeatureRegistry.getClassByName("A"))
        assertEquals(FeatB::class, FeatureRegistry.getClassByName("B"))
        assertEquals(FeatC::class, FeatureRegistry.getClassByName("C"))
        assertEquals(FeatD::class, FeatureRegistry.getClassByName("D"))
        assertEquals(FeatE::class, FeatureRegistry.getClassByName("E"))
        assertEquals(FeatF::class, FeatureRegistry.getClassByName("F"))
        assertEquals(FeatG::class, FeatureRegistry.getClassByName("G"))
        assertEquals(FeatH::class, FeatureRegistry.getClassByName("H"))
    }

    @Test
    fun testGetFeatureNames() {
        assertTrue(FeatureRegistry.getFeatureNames().containsAll(setOf("A", "B", "C", "D", "E", "F", "G", "H")))
    }

    @Test
    fun testFeatureDependencies() {
        val dependenciesField = FeatureRegistry::class.java.getDeclaredField("featureDependencies")
        dependenciesField.setAccessible(true)
        @Suppress("UNCHECKED_CAST")
        val dependencies = dependenciesField.get(FeatureRegistry) as MutableMap<KClass<out Feature>, Set<KClass<out Feature>>>
        for ((featureClass, dependencySet) in dependencies) {
            assertEquals(FeatureRegistry.getProviderByClass(featureClass)!!.featureDependencies, dependencySet)
        }
    }

    @Test
    fun testFeatureActivationOrder() {
        fun orderTest(featureClass: KClass<out Feature>, expected: List<KClass<out Feature>>) {
            val activationOrder = FeatureRegistry.getFeatureActivationOrder(featureClass)
            assertEquals(expected, activationOrder)
        }

        orderTest(FeatA::class, listOf(FeatA::class))
        orderTest(FeatB::class, listOf(FeatA::class, FeatB::class))
        orderTest(FeatC::class, listOf(FeatA::class, FeatC::class))
        orderTest(FeatD::class, listOf(FeatD::class))
        orderTest(FeatE::class, listOf(FeatA::class, FeatB::class, FeatE::class))
        orderTest(FeatF::class, listOf(FeatA::class, FeatC::class, FeatD::class, FeatF::class))
        orderTest(FeatG::class, listOf(FeatA::class, FeatB::class, FeatE::class, FeatC::class, FeatD::class, FeatF::class, FeatG::class))
        orderTest(FeatH::class, listOf(FeatA::class, FeatC::class, FeatD::class, FeatF::class, FeatH::class))
    }

    @Test
    fun testCyclicDependency() {

        val dependenciesField = FeatureRegistry::class.java.getDeclaredField("featureDependencies")
        dependenciesField.setAccessible(true)
        @Suppress("UNCHECKED_CAST")
        val dependencies = dependenciesField.get(FeatureRegistry) as MutableMap<KClass<out Feature>, Set<KClass<out Feature>>>
        dependencies.clear()
        dependencies.putAll(mutableMapOf<KClass<out Feature>, Set<KClass<out Feature>>>(
            Pair(FeatA::class, setOf(FeatB::class)), Pair(FeatB::class, setOf(FeatC::class)), Pair(FeatC::class, setOf(FeatA::class)))
        )

        assertThrows<IllegalStateException> { FeatureRegistry.getFeatureActivationOrder(FeatA::class) }

    }
}