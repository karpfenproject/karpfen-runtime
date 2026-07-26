package io.karpfen.features

import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 20, time = 2, timeUnit = BenchmarkTimeUnit.SECONDS)
@State(Scope.Benchmark)
abstract class BenchmarkSettings {
}