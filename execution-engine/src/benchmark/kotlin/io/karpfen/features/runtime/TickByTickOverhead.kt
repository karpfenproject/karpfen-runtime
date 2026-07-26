package io.karpfen.features.runtime

import io.karpfen.features.ComplexStateMachineBenchmark
import io.karpfen.io.karpfen.features.runtime.TickByTickFeature
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Setup
import kotlinx.benchmark.TearDown

class TickByTickOverhead: ComplexStateMachineBenchmark() {

    @Setup
    fun featureActivation() {
        this.featureManager.requestFeatureActivation(TickByTickFeature::class)
    }

    @TearDown
    fun featureDeactivation() {
        this.featureManager.requestFeatureDeactivation(TickByTickFeature::class)
    }

    @Benchmark
    fun idle() {
        this.startBenchmark()
    }

}