package io.karpfen.features

import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class FeatureTest {

    @Test
    fun testDependantAdditionAndRemoval() {

        val dummyFeature = object : DefaultFeature(false) {}

        val dependants = mutableSetOf<KClass<out Feature>>(
            object : DefaultFeature(true) {}::class,
            object : DefaultFeature(false) {}::class,
            object : DefaultFeature(true) {}::class,
        )

        for (dependant in dependants) {
            dummyFeature.addDependant(dependant)
        }

        assertEquals(dependants, dummyFeature.dependants)

        for (dependant in dependants) {
            dummyFeature.removeDependant(dependant)
        }

        assertEquals(0, dummyFeature.dependants.size)
    }

    @Test
    fun testSafeToCleanUp() {
        val featureAddedByUser = object : DefaultFeature(isUserAdded = true) {}
        val featureWithDependant = object : DefaultFeature(isUserAdded = false) {}.also { it.addDependant(DefaultFeature::class) }
        val safeToCleanFeature = object : DefaultFeature(isUserAdded = false) {}

        assertFalse(featureAddedByUser.safeToCleanUp())
        assertFalse(featureWithDependant.safeToCleanUp())
        assertTrue(safeToCleanFeature.safeToCleanUp())
    }

    @Test
    fun testDeletionRequest() {
        val featureToBeDeleted = object : DefaultFeature(isUserAdded = false) {}

        assertTrue(featureToBeDeleted.requestDeletion())
        assertFalse(featureToBeDeleted.requestDeletion())
    }

}