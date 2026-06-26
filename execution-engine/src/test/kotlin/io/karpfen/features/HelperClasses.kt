package io.karpfen.features

import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import kotlin.reflect.KClass

open class FeatA(explicitlyRequested: Boolean): DefaultFeature(explicitlyRequested)
class FeatB(explicitlyRequested: Boolean, val featA: FeatA): DefaultFeature(explicitlyRequested)
class FeatC(explicitlyRequested: Boolean, val featA: FeatA): DefaultFeature(explicitlyRequested)
class FeatD(explicitlyRequested: Boolean): DefaultFeature(explicitlyRequested)
class FeatE(explicitlyRequested: Boolean, val featB: FeatB): DefaultFeature(explicitlyRequested)
class FeatF(explicitlyRequested: Boolean, val featC: FeatC, val featD: FeatD): DefaultFeature(explicitlyRequested)
class FeatG(explicitlyRequested: Boolean, val featE: FeatE, val featF: FeatF): DefaultFeature(explicitlyRequested)
class FeatH(explicitlyRequested: Boolean, val featF: FeatF): DefaultFeature(explicitlyRequested)

class FeatAProvider(): FeatureProvider {
    override val registryName: String = "A"
    override val registryClass: KClass<out Feature> = FeatA::class
    override val featureDependencies: Set<KClass<out Feature>> = emptySet()

    override fun createFeature(explicitlyRequested: Boolean, manager: FeatureManager): Feature {
        return FeatA(explicitlyRequested)
    }
}

class FeatBProvider(): FeatureProvider {
    override val registryName: String = "B"
    override val registryClass: KClass<out Feature> = FeatB::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatA::class)

    override fun createFeature(explicitlyRequested: Boolean, manager: FeatureManager): Feature {
        return FeatB(explicitlyRequested, manager.getActiveFeatureAsClass<FeatA>())
    }
}

class FeatCProvider(): FeatureProvider {
    override val registryName: String = "C"
    override val registryClass: KClass<out Feature> = FeatC::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatA::class)

    override fun createFeature(explicitlyRequested: Boolean, manager: FeatureManager): Feature {
        return FeatC(explicitlyRequested, manager.getActiveFeatureAsClass<FeatA>())
    }
}

class FeatDProvider(): FeatureProvider {
    override val registryName: String = "D"
    override val registryClass: KClass<out Feature> = FeatD::class
    override val featureDependencies: Set<KClass<out Feature>> = emptySet()

    override fun createFeature(explicitlyRequested: Boolean, manager: FeatureManager): Feature {
        return FeatD(explicitlyRequested)
    }
}

class FeatEProvider(): FeatureProvider {
    override val registryName: String = "E"
    override val registryClass: KClass<out Feature> = FeatE::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatB::class)

    override fun createFeature(explicitlyRequested: Boolean, manager: FeatureManager): Feature {
        return FeatE(explicitlyRequested, manager.getActiveFeatureAsClass<FeatB>())
    }
}

class FeatFProvider(): FeatureProvider {
    override val registryName: String = "F"
    override val registryClass: KClass<out Feature> = FeatF::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatC::class, FeatD::class)

    override fun createFeature(explicitlyRequested: Boolean, manager: FeatureManager): Feature {
        return FeatF(explicitlyRequested, manager.getActiveFeatureAsClass<FeatC>(), manager.getActiveFeatureAsClass<FeatD>())
    }
}

class FeatGProvider(): FeatureProvider {
    override val registryName: String = "G"
    override val registryClass: KClass<out Feature> = FeatG::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatE::class, FeatF::class)

    override fun createFeature(explicitlyRequested: Boolean, manager: FeatureManager): Feature {
        return FeatG(explicitlyRequested, manager.getActiveFeatureAsClass<FeatE>(), manager.getActiveFeatureAsClass<FeatF>())
    }
}

class FeatHProvider(): FeatureProvider {
    override val registryName: String = "H"
    override val registryClass: KClass<out Feature> = FeatH::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatF::class)

    override fun createFeature(explicitlyRequested: Boolean, manager: FeatureManager): Feature {
        return FeatH(explicitlyRequested, manager.getActiveFeatureAsClass<FeatF>())
    }
}

class ExecutionFeature: DefaultFeature(true) {
    override fun onActivate() {
        throw RuntimeException("Simulate Execution of onActivate")
    }
    override fun onDeactivate() {
        throw RuntimeException("Simulate Execution of onDeactivate")
    }

    override fun onMessage(message: String): String {
        throw RuntimeException("Simulate Execution of onMessage with message: $message")
    }

    fun execute(vararg params: String) {
        throw RuntimeException("Simulate Execution of execute with Parameters: ${params.contentToString()}")
    }
}

class ExecutionProvider: FeatureProvider {
    override val registryName: String = "execution"
    override val registryClass: KClass<out Feature> = ExecutionFeature::class
    override val featureDependencies: Set<KClass<out Feature>> = emptySet()
    override fun createFeature(explicitlyRequested: Boolean, manager: FeatureManager): Feature {
        return ExecutionFeature()
    }
}