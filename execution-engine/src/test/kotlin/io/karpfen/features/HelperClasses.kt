package io.karpfen.features

import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import kotlin.reflect.KClass

open class DummyFactoryFeature(isUserAdded: Boolean) : DefaultFeature(isUserAdded)

val objectMap = arrayOf(
    object: DummyFactoryFeature(true) {},
    object: DummyFactoryFeature(false) {},
    object: DummyFactoryFeature(true) {}
)

class DummyFeatureProvider0 : FeatureProvider {
    override val registryName = "dummy0"
    override val registryClass = objectMap[0]::class
    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager) = objectMap[0]
}

class DummyFeatureProvider1 : FeatureProvider {
    override val registryName = "dummy1"
    override val registryClass = objectMap[1]::class
    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager) = objectMap[1]
}

class DummyFeatureProvider2 : FeatureProvider {
    override val registryName = "dummy2"
    override val registryClass = objectMap[2]::class
    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager) = objectMap[2]
}

open class DummyManagerFeature(override val dependencies: Set<KClass<out Feature>>, isUserAdded: Boolean) : DefaultFeature(isUserAdded)

class DummyExecutionFeature : DummyManagerFeature(setOf(), false) {
    override fun onActivate() {
        throw RuntimeException("Simulate Execution of onAdd")
    }

    override fun onDeactivate() {
        throw RuntimeException("Simulate Execution of onRemove")
    }

    override fun onMessage(message: String) {
        throw RuntimeException("Simulate Execution of onMessage with message: $message")
    }

    fun execute(vararg params: String) {
        throw RuntimeException("Simulate Execution of execute with Parameters ${params.contentToString()}")
    }
}

class FeatA(isUserAdded: Boolean) : DummyManagerFeature(setOf(), isUserAdded)
class FeatB(isUserAdded: Boolean) : DummyManagerFeature(setOf(FeatA::class), isUserAdded)
class FeatC(isUserAdded: Boolean) : DummyManagerFeature(setOf(FeatA::class), isUserAdded)
class FeatD(isUserAdded: Boolean) : DummyManagerFeature(setOf(), isUserAdded)
class FeatE(isUserAdded: Boolean) : DummyManagerFeature(setOf(FeatB::class), isUserAdded)
class FeatF(isUserAdded: Boolean) : DummyManagerFeature(setOf(FeatC::class, FeatD::class), isUserAdded)
class FeatG(isUserAdded: Boolean) : DummyManagerFeature(setOf(FeatE::class, FeatF::class), isUserAdded)
class FeatH(isUserAdded: Boolean) : DummyManagerFeature(setOf(FeatF::class), isUserAdded)

class FeatAProvider() : FeatureProvider {
    override val registryName = "A"
    override val registryClass = FeatA::class
    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager): Feature = FeatA(isUserAdded)
}
class FeatBProvider() : FeatureProvider {
    override val registryName = "B"
    override val registryClass = FeatB::class
    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager): Feature = FeatB(isUserAdded)
}
class FeatCProvider() : FeatureProvider {
    override val registryName = "C"
    override val registryClass = FeatC::class
    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager): Feature = FeatC(isUserAdded)
}
class FeatDProvider() : FeatureProvider {
    override val registryName = "D"
    override val registryClass = FeatD::class
    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager): Feature = FeatD(isUserAdded)
}
class FeatEProvider() : FeatureProvider {
    override val registryName = "E"
    override val registryClass = FeatE::class
    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager): Feature = FeatE(isUserAdded)
}
class FeatFProvider() : FeatureProvider {
    override val registryName = "F"
    override val registryClass = FeatF::class
    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager): Feature = FeatF(isUserAdded)
}
class FeatGProvider() : FeatureProvider {
    override val registryName = "G"
    override val registryClass = FeatG::class
    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager): Feature = FeatG(isUserAdded)
}
class FeatHProvider() : FeatureProvider {
    override val registryName = "H"
    override val registryClass = FeatH::class
    override fun createFeature(isUserAdded: Boolean, manager: FeatureManager): Feature = FeatH(isUserAdded)
}
