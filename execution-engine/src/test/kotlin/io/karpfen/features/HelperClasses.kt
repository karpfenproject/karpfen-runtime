package io.karpfen.features

import com.google.auto.service.AutoService
import io.karpfen.io.karpfen.features.DefaultFeature
import io.karpfen.io.karpfen.features.Feature
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.FeatureProvider
import kotlin.reflect.KClass

open class FeatA(): DefaultFeature()
class FeatB(val featA: FeatA): DefaultFeature()
class FeatC(val featA: FeatA): DefaultFeature()
class FeatD(): DefaultFeature()
class FeatE(val featB: FeatB): DefaultFeature()
class FeatF(val featC: FeatC, val featD: FeatD): DefaultFeature()
class FeatG(val featE: FeatE, val featF: FeatF): DefaultFeature()
class FeatH(val featF: FeatF): DefaultFeature()

@AutoService(FeatureProvider::class)
class FeatAProvider(): FeatureProvider {
    override val registryName: String = "A"
    override val registryClass: KClass<out Feature> = FeatA::class
    override val featureDependencies: Set<KClass<out Feature>> = emptySet()

    override fun createFeature(manager: FeatureManager): Feature {
        return FeatA()
    }
}

@AutoService(FeatureProvider::class)
class FeatBProvider(): FeatureProvider {
    override val registryName: String = "B"
    override val registryClass: KClass<out Feature> = FeatB::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatA::class)

    override fun createFeature(manager: FeatureManager): Feature {
        return FeatB(manager.getActiveFeature<FeatA>())
    }
}

@AutoService(FeatureProvider::class)
class FeatCProvider(): FeatureProvider {
    override val registryName: String = "C"
    override val registryClass: KClass<out Feature> = FeatC::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatA::class)

    override fun createFeature(manager: FeatureManager): Feature {
        return FeatC(manager.getActiveFeature<FeatA>())
    }
}

@AutoService(FeatureProvider::class)
class FeatDProvider(): FeatureProvider {
    override val registryName: String = "D"
    override val registryClass: KClass<out Feature> = FeatD::class
    override val featureDependencies: Set<KClass<out Feature>> = emptySet()

    override fun createFeature(manager: FeatureManager): Feature {
        return FeatD()
    }
}

@AutoService(FeatureProvider::class)
class FeatEProvider(): FeatureProvider {
    override val registryName: String = "E"
    override val registryClass: KClass<out Feature> = FeatE::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatB::class)

    override fun createFeature(manager: FeatureManager): Feature {
        return FeatE(manager.getActiveFeature<FeatB>())
    }
}

@AutoService(FeatureProvider::class)
class FeatFProvider(): FeatureProvider {
    override val registryName: String = "F"
    override val registryClass: KClass<out Feature> = FeatF::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatC::class, FeatD::class)

    override fun createFeature(manager: FeatureManager): Feature {
        return FeatF(manager.getActiveFeature<FeatC>(), manager.getActiveFeature<FeatD>())
    }
}

@AutoService(FeatureProvider::class)
class FeatGProvider(): FeatureProvider {
    override val registryName: String = "G"
    override val registryClass: KClass<out Feature> = FeatG::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatE::class, FeatF::class)

    override fun createFeature(manager: FeatureManager): Feature {
        return FeatG(manager.getActiveFeature<FeatE>(), manager.getActiveFeature<FeatF>())
    }
}

@AutoService(FeatureProvider::class)
class FeatHProvider(): FeatureProvider {
    override val registryName: String = "H"
    override val registryClass: KClass<out Feature> = FeatH::class
    override val featureDependencies: Set<KClass<out Feature>> = setOf(FeatF::class)

    override fun createFeature(manager: FeatureManager): Feature {
        return FeatH(manager.getActiveFeature<FeatF>())
    }
}

class ExecutionFeature: DefaultFeature() {
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

@AutoService(FeatureProvider::class)
class ExecutionProvider: FeatureProvider {
    override val registryName: String = "execution"
    override val registryClass: KClass<out Feature> = ExecutionFeature::class
    override val featureDependencies: Set<KClass<out Feature>> = emptySet()
    override fun createFeature(manager: FeatureManager): Feature {
        return ExecutionFeature()
    }
}