package io.karpfen.features

import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureFactory
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureRegistry
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.iterator
import kotlin.collections.mutableMapOf
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureManagerTest {

    lateinit var manager: FeatureManager

    @BeforeEach
    fun init() {
        manager = FeatureManager()
    }

    @Test
    fun testRequestFeatureUpdate() {
        val isUpdatingField = manager::class.java.getDeclaredField("isUpdatingFeatures")
        isUpdatingField.setAccessible(true)
        assertFalse((isUpdatingField.get(manager) as AtomicBoolean).get())

        val requestUpdateMethod = manager::class.java.getDeclaredMethod("requestFeatureUpdate")
        requestUpdateMethod.setAccessible(true)
        assertTrue(requestUpdateMethod.invoke(manager) as Boolean)
        assertTrue((isUpdatingField.get(manager) as AtomicBoolean).get())

        val unlockUpdateMethod = manager::class.java.getDeclaredMethod("unlockFeatureUpdate")
        unlockUpdateMethod.setAccessible(true)
        unlockUpdateMethod.invoke(manager)
        assertFalse((isUpdatingField.get(manager) as AtomicBoolean).get())

    }

    @Test
    fun testActiveFeatures() {
        val features = setOf<KClass<out Feature>>(
            FeatA::class,
            FeatB::class,
            FeatC::class,
        )

        for (featureClass in features) {
            manager.requestFeatureActivation(featureClass)
        }

        for (featureClass in features) {
            val feature = manager.getActiveFeature(featureClass)
            assertNotNull(feature)
            assertEquals(featureClass, feature::class)
        }

        val managerFeatures = manager.getActiveFeaturesClasses();

        assertTrue(features.size == managerFeatures.size && managerFeatures.containsAll(features) && features.containsAll(managerFeatures))

    }

    @Test
    fun testIndependentFeatures() {
        val features = arrayOf<KClass<out Feature>>(
            FeatA::class,
            FeatD::class
        )

        for (feature in features) {
            assertTrue(manager.requestFeatureActivation(feature))
            assertFalse(manager.requestFeatureActivation(feature))
        }
        
        val dependantsField = manager::class.java.getDeclaredField("localDependantsRegistry")
        dependantsField.setAccessible(true)
        @Suppress("UNCHECKED_CAST")
        val dependantsMap = dependantsField.get(manager) as MutableMap<KClass<out Feature>, MutableSet<KClass<out Feature>>>

        val activeFeaturesField = manager::class.java.getDeclaredField("activeFeatureRegistry")
        activeFeaturesField.setAccessible(true)
        @Suppress("UNCHECKED_CAST")
        val activeFeatureRegistry = activeFeaturesField.get(manager) as ConcurrentHashMap<KClass<out Feature>, Feature>

        for (featureClass in features) {
            val dependants = dependantsMap[featureClass] ?: emptySet()
            assertEquals(0, dependants.size)
        }

        for (i in features.size downTo 1) {
            assertEquals(i, activeFeatureRegistry.size)
            assertTrue(manager.requestFeatureDeactivation(features[i - 1]))
            assertFalse(manager.requestFeatureDeactivation(features[i - 1]))
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

        val dependantsField = manager::class.java.getDeclaredField("localDependantsRegistry")
        dependantsField.setAccessible(true)
        @Suppress("UNCHECKED_CAST")
        val dependantsMap = dependantsField.get(manager) as MutableMap<KClass<out Feature>, MutableSet<KClass<out Feature>>>

        fun generateDependencyTree() {
            //Simulate addition of A, G, H
            manager.requestFeatureActivation(FeatA::class)
            manager.requestFeatureActivation(FeatG::class)
            manager.requestFeatureActivation(FeatH::class)

            //Check if Dependency tree is correct
            assertEquals(setOf<KClass<out Feature>>(FeatA::class, FeatB::class, FeatC::class, FeatD::class, FeatE::class, FeatF::class, FeatG::class, FeatH::class), manager.getActiveFeaturesClasses())

            for (featureClass in setOf(FeatA::class, FeatB::class, FeatC::class, FeatD::class, FeatE::class, FeatF::class, FeatG::class, FeatH::class)) {
                when (featureClass) {
                    FeatA::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatB::class, FeatC::class), dependantsMap[featureClass] ?: emptySet())
                        val feature = manager.getActiveFeature(featureClass)
                        assertNotNull(feature)
                        assertTrue(feature.explicitlyRequested)
                    }
                    FeatB::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatE::class), dependantsMap[featureClass] ?: emptySet())
                        val feature = manager.getActiveFeature(featureClass)
                        assertNotNull(feature)
                        assertFalse(feature.explicitlyRequested)
                    }
                    FeatC::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatF::class), dependantsMap[featureClass] ?: emptySet())
                        val feature = manager.getActiveFeature(featureClass)
                        assertNotNull(feature)
                        assertFalse(feature.explicitlyRequested)
                    }
                    FeatD::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatF::class), dependantsMap[featureClass] ?: emptySet())
                        val feature = manager.getActiveFeature(featureClass)
                        assertNotNull(feature)
                        assertFalse(feature.explicitlyRequested)
                    }
                    FeatE::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatG::class), dependantsMap[featureClass] ?: emptySet())
                        val feature = manager.getActiveFeature(featureClass)
                        assertNotNull(feature)
                        assertFalse(feature.explicitlyRequested)
                    }
                    FeatF::class -> {
                        assertEquals(setOf<KClass<out Feature>>(FeatG::class, FeatH::class), dependantsMap[featureClass] ?: emptySet())
                        val feature = manager.getActiveFeature(featureClass)
                        assertNotNull(feature)
                        assertFalse(feature.explicitlyRequested)
                    }
                    FeatG::class -> {
                        assertEquals(setOf(), dependantsMap[featureClass] ?: emptySet())
                        val feature = manager.getActiveFeature(featureClass)
                        assertNotNull(feature)
                        assertTrue(feature.explicitlyRequested)
                    }
                    FeatH::class -> {
                        assertEquals(setOf(), dependantsMap[featureClass] ?: emptySet())
                        val feature = manager.getActiveFeature(featureClass)
                        assertNotNull(feature)
                        assertTrue(feature.explicitlyRequested)
                    }
                }
            }
        }

        //Test1
        generateDependencyTree()

        //Simulate Removal of C
        assertTrue(manager.requestFeatureDeactivation(FeatC::class))

        //Check that only FeatA is still present
        assertEquals(setOf(FeatA::class), manager.getActiveFeaturesClasses())

        assertEquals(mutableMapOf<KClass<out Feature>, MutableSet<KClass<out Feature>>>(
            Pair(FeatA::class, mutableSetOf())
        ), dependantsMap)


        //Test2
        generateDependencyTree()

        //Simulate Removal of E
        assertTrue(manager.requestFeatureDeactivation(FeatE::class))

        //Check that only FeatA, FeatC, FeatD, FeatF, FeatH are still present
        assertEquals(setOf(FeatA::class, FeatC::class, FeatD::class, FeatF::class, FeatH::class), manager.getActiveFeaturesClasses())

        assertEquals(mutableMapOf<KClass<out Feature>, MutableSet<KClass<out Feature>>>(
            Pair(FeatA::class, mutableSetOf(FeatC::class)),
            Pair(FeatC::class, mutableSetOf(FeatF::class)),
            Pair(FeatD::class, mutableSetOf(FeatF::class)),
            Pair(FeatF::class, mutableSetOf(FeatH::class)),
        ), dependantsMap)


        //Test3
        generateDependencyTree()

        //Simulate Removal of A
        assertTrue(manager.requestFeatureDeactivation(FeatA::class))

        //Check that no feature is still present
        assertEquals(setOf(), manager.getActiveFeaturesClasses())

        assertEquals(mutableMapOf(), dependantsMap)
    }

    @Test
    fun testFeatureFunctionExecution() {
        //Method execution is simulated by throwing exceptions
        //onActivate
        var exception: RuntimeException = assertThrows {
            manager.requestFeatureActivation(ExecutionFeature::class)
        }

        assertEquals("Simulate Execution of onActivate", exception.message)

        assertDoesNotThrow { manager.requestFeatureActivation(ExecutionFeature::class) }

        //does execute
        exception = assertThrows {
            manager.executeIfPresent<ExecutionFeature> { feature -> feature.execute("additional", "parameters") }
        }

        assertEquals("Simulate Execution of execute with Parameters: ${arrayOf("additional", "parameters").contentToString()}", exception.message)

        //does receive message
        exception = assertThrows {
            manager.onMessage(ExecutionFeature::class, "test")
        }

        assertEquals("Simulate Execution of onMessage with message: test", exception.message)

        //onDeactivate
        exception = assertThrows {
            manager.requestFeatureDeactivation(ExecutionFeature::class)
        }

        assertEquals("Simulate Execution of onDeactivate", exception.message)

        assertDoesNotThrow { manager.requestFeatureDeactivation(ExecutionFeature::class) }

        //does not execute
        assertDoesNotThrow { manager.executeIfPresent<ExecutionFeature> { feature -> feature.execute() } }

        //does not receive message
        assertNull(manager.onMessage(ExecutionFeature::class, "test"))
    }
}