package io.karpfen.features

import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureFactory
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureRegistry
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.collections.iterator
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureManagerTest {

    lateinit var manager: FeatureManager

    @BeforeEach
    fun init() {
        manager = FeatureManager()

    }

    @Test
    fun testRequestFeatureUpdate() {
        assertTrue(manager.requestFeatureUpdate())
        assertFalse(manager.requestFeatureUpdate())

        manager.unlockFeatureUpdate()

        assertTrue(manager.requestFeatureUpdate())
    }

    @Test
    fun testActiveFeatures() {
        val features = arrayOf<Feature>(
            object : DummyManagerFeature(setOf(), true) {},
            object : DummyManagerFeature(setOf(), false) {},
            object : DummyManagerFeature(setOf(), false) {},
        )

        for (feature in features) {
            manager.addFeature(feature)
        }

        val expectedFeatures = features.map {feature -> feature::class}
        val managerFeatures = manager.getActiveFeaturesClasses();

        assertTrue(expectedFeatures.size == managerFeatures.size && managerFeatures.containsAll(expectedFeatures) && expectedFeatures.containsAll(managerFeatures))

    }

    @Test
    fun testIndependentFeatures() {
        val features = arrayOf<Feature>(
            object : DummyManagerFeature(setOf(), true) {},
            object : DummyManagerFeature(setOf(), false) {},
            object : DummyManagerFeature(setOf(), false) {},
        )

        for (feature in features) {
            assertTrue(manager.addFeature(feature))
            assertFalse(manager.addFeature(feature))
        }

        for (feature in features) {
            assertEquals(0, feature.dependants.size)
        }

        for (i in features.size downTo 1) {
            assertEquals(i, manager.activeFeatureRegistry.size)
            assertTrue(manager.removeFeature(features[i - 1]))
            assertFalse(manager.removeFeature(features[i - 1]))
        }
    }

    @Test
    fun testDependentFeatures() {
        /* A visualization of the Dependency Tree (Top -> Down = Independent -> Dependent)
        Features marked with || are added by user

           |FeatA|
             / \
            /   \
           /     \
        FeatB   FeatC   FeatD
          |        \     /
          |         \   /
          |          \ /
        FeatE       FeatF
           \         / \
            \       /   \
             \     /     \
             |FeatG|   |FeatH| */


        val featA = FeatureFactory.createFeature(FeatA::class, true, manager)
        val featG = FeatureFactory.createFeature(FeatG::class, true, manager)
        val featH = FeatureFactory.createFeature(FeatH::class, true, manager)

        fun generateDependencyTree() {
            //Simulate addition of A, G, H
            manager.addFeature(featA)
            manager.addFeature(featG)
            manager.addFeature(featH)

            //Check if Dependency tree is correct
            assertEquals(setOf<KClass<out Feature>>(FeatA::class, FeatB::class, FeatC::class, FeatD::class, FeatE::class, FeatF::class, FeatG::class, FeatH::class), manager.activeFeatureRegistry.keys)

            for ((featureClass, feature) in manager.activeFeatureRegistry) {
                when (featureClass) {
                    FeatA::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatB::class, FeatC::class), feature.dependants)
                        assertTrue(feature.isUserAdded)
                    }
                    FeatB::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatE::class), feature.dependants)
                        assertFalse(feature.isUserAdded)
                    }
                    FeatC::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatF::class), feature.dependants)
                        assertFalse(feature.isUserAdded)
                    }
                    FeatD::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatF::class), feature.dependants)
                        assertFalse(feature.isUserAdded)
                    }
                    FeatE::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatG::class), feature.dependants)
                        assertFalse(feature.isUserAdded)
                    }
                    FeatF::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatG::class, FeatH::class), feature.dependants)
                        assertFalse(feature.isUserAdded)
                    }
                    FeatG::class -> {
                        assertEquals(setOf(), feature.dependants)
                        assertTrue(feature.isUserAdded)
                    }
                    FeatH::class -> {
                        assertEquals(setOf(), feature.dependants)
                        assertTrue(feature.isUserAdded)
                    }
                }
            }
        }

        //Test1
        generateDependencyTree()

        //Simulate Removal of C
        var registryBeforeRemoval = manager.activeFeatureRegistry

        assertTrue(manager.removeFeature(manager.getFeature(FeatC::class)!!))

        //Check that only FeatA is still present
        assertEquals(setOf(FeatA::class), manager.activeFeatureRegistry.keys)

        //Check that all Features have no dependants
        for ((_, feature) in registryBeforeRemoval) {
            assertEquals(setOf(), feature.dependants)
        }



        //Test2
        generateDependencyTree()

        //Simulate Removal of E
        registryBeforeRemoval = manager.activeFeatureRegistry

        assertTrue(manager.removeFeature(manager.getFeature(FeatE::class)!!))

        //Check that only B, E and G are missing
        assertEquals(setOf(FeatA::class, FeatC::class, FeatD::class, FeatF::class, FeatH::class), manager.activeFeatureRegistry.keys)

        //Check that all Features correct dependants
        for ((featureClass, feature) in registryBeforeRemoval) {
            when (featureClass) {
                FeatA::class -> assertEquals(setOf<KClass<out Feature>>(FeatC::class), feature.dependants)
                FeatB::class -> assertEquals(setOf(), feature.dependants)
                FeatC::class -> assertEquals(setOf<KClass<out Feature>>(FeatF::class), feature.dependants)
                FeatD::class -> assertEquals(setOf<KClass<out Feature>>(FeatF::class), feature.dependants)
                FeatE::class -> assertEquals(setOf(), feature.dependants)
                FeatF::class -> assertEquals(setOf<KClass<out Feature>>(FeatH::class), feature.dependants)
                FeatG::class -> assertEquals(setOf(), feature.dependants)
                FeatH::class -> assertEquals(setOf(), feature.dependants)
            }
        }


        //Test3
        generateDependencyTree()

        //Simulate removal of A
        registryBeforeRemoval = manager.activeFeatureRegistry

        assertTrue(manager.removeFeature(manager.getFeature(FeatA::class)!!))

        //Check that nothing is present anymore
        assertEquals(setOf(), manager.activeFeatureRegistry.keys)

        //Check that all Features have no dependants
        for ((_, feature) in registryBeforeRemoval) {
            assertEquals(0, feature.dependants.size)
        }
    }

    @Test
    fun testFeatureFunctionExecution() {
        val dummyExecutionFeature = DummyExecutionFeature()

        //onAdd
        var exception: RuntimeException = assertThrows {
            manager.addFeature(dummyExecutionFeature)
        }

        assertEquals("Simulate Execution of onAdd", exception.message)

        assertDoesNotThrow { manager.addFeature(dummyExecutionFeature) }

        //does execute
        exception = assertThrows {
            manager.executeIfPresent<DummyExecutionFeature> { feature -> feature.execute("additional", "parameters") }
        }

        assertEquals("Simulate Execution of execute with Parameters ${arrayOf("additional", "parameters").contentToString()}", exception.message)

        //does receive message
        exception = assertThrows {
            manager.onMessage(dummyExecutionFeature::class, "test")
        }

        assertEquals("Simulate Execution of onMessage with message: test", exception.message)

        //onRemove
        exception = assertThrows {
            manager.removeFeature(dummyExecutionFeature)
        }

        assertEquals("Simulate Execution of onRemove", exception.message)

        assertDoesNotThrow { manager.removeFeature(dummyExecutionFeature) }

        //does not execute
        assertDoesNotThrow { manager.executeIfPresent<DummyExecutionFeature> { feature -> feature.execute() } }

        //does not receive message
        exception = assertThrows {
            manager.onMessage(dummyExecutionFeature::class, "test")
        }

        assertEquals("${FeatureRegistry.getNameByClass(dummyExecutionFeature::class)} has not been activated", exception.message)
    }
}