package io.karpfen.features.runtime

import io.karpfen.features.ComplexStateMachineBenchmark
import io.karpfen.io.karpfen.features.runtime.BreakpointFeature
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Setup
import kotlinx.benchmark.TearDown

class BreakpointOverhead: ComplexStateMachineBenchmark() {

    @Setup
    fun featureActivation() {
        this.featureManager.requestFeatureActivation(BreakpointFeature::class)
    }

    @TearDown
    fun featureDeactivation() {
        this.featureManager.requestFeatureDeactivation(BreakpointFeature::class)
    }

    @Benchmark
    fun idle() {
        this.startBenchmark()
    }
}