package io.karpfen.features.language

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import dsl.textual.KstatesDSLConverter
import io.karpfen.Engine
import io.karpfen.EngineTraceLogger
import io.karpfen.features.BenchmarkSettings
import io.karpfen.features.ComplexStateMachineBenchmark
import io.karpfen.io.karpfen.exec.SMContext
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.features.language.TickLimitFeature
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Setup
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Level

class TickLimitOverhead: ComplexStateMachineBenchmark() {

    @Setup
    fun featureActivation() {
        this.featureManager.requestFeatureActivation(TickLimitFeature::class)
    }

    @TearDown
    fun featureDeactivation() {
        this.featureManager.requestFeatureDeactivation(TickLimitFeature::class)
    }

    @Benchmark
    fun idle() {
        this.startBenchmark()
    }
}


class TickLimitOverheadSpecializedNotSupported(): BenchmarkSettings() {
    lateinit var engine: Engine

    lateinit var contexts: List<SMContext>

    val featureManager: FeatureManager = FeatureManager()

    val globalMetamodelId = "TickLimitModel"

    val globalModelElementId = "tickLimitModel"

    val metamodel = KmetaDSLConverter.parseKmetaString("type \"$globalMetamodelId\" \"custom state machine not supporting tick limit\" {}".trimIndent())

    val modelDefinition = "make object \"$globalModelElementId\": \"$globalMetamodelId\" {}".trimIndent()

    val statemachineMap = mapOf(Pair(globalModelElementId, KstatesDSLConverter.parseKstatesString("STATEMACHINE ATTACHED TO \"$globalMetamodelId\" {\n" +
            "\tSTATES {\n" +
            "\t\tSTATE \"delayTest\" {\n" +
            "\t\t\tINITIAL STATE \"5TicksBeforeDelay\" {}\n" +
            "\t\t\tSTATE \"4TicksBeforeDelay\" {}\n" +
            "\t\t\tSTATE \"3TicksBeforeDelay\" {}\n" +
            "\t\t\tSTATE \"2TicksBeforeDelay\" {}\n" +
            "\t\t\tSTATE \"1TickBeforeDelay\" {}\n" +
            "\t\t}\n" +
            "\t\tSTATE \"timeoutTest\" {\n" +
            "\t\t\tSTATE \"5TicksBeforeTimeout\" {}\n" +
            "\t\t\tSTATE \"4TicksBeforeTimeout\" {}\n" +
            "\t\t\tSTATE \"3TicksBeforeTimeout\" {}\n" +
            "\t\t\tSTATE \"2TicksBeforeTimeout\" {}\n" +
            "\t\t\tSTATE \"1TickBeforeTimeout\" {}\n" +
            "\t\t}\n" +
            "\t}\n" +
            "\tTRANSITIONS {\n" +
            "\t\tTRANSITION \"5TicksBeforeDelay\" -> \"4TicksBeforeDelay\" {}\n" +
            "\t\tTRANSITION \"4TicksBeforeDelay\" -> \"3TicksBeforeDelay\" {}\n" +
            "\t\tTRANSITION \"3TicksBeforeDelay\" -> \"2TicksBeforeDelay\" {}\n" +
            "\t\tTRANSITION \"2TicksBeforeDelay\" -> \"1TickBeforeDelay\" {}\n" +
            "\t\tTRANSITION \"1TickBeforeDelay\" -> \"5TicksBeforeTimeout\" {}\n" +
            "\t\tTRANSITION \"5TicksBeforeTimeout\" -> \"4TicksBeforeTimeout\" {}\n" +
            "\t\tTRANSITION \"4TicksBeforeTimeout\" -> \"3TicksBeforeTimeout\" {}\n" +
            "\t\tTRANSITION \"3TicksBeforeTimeout\" -> \"2TicksBeforeTimeout\" {}\n" +
            "\t\tTRANSITION \"2TicksBeforeTimeout\" -> \"1TickBeforeTimeout\" {}\n" +
            "\t\tTRANSITION \"1TickBeforeTimeout\" -> \"5TicksBeforeDelay\" {}\n" +
            "\t}\n" +
            "}".trimIndent())))

    @Setup(Level.Invocation)
    fun setup() {
        engine = Engine(
            metamodel = metamodel,
            model = KmodelDSLConverter.parseKmodelString(modelDefinition, metamodel),
            statemachineMap = statemachineMap,
            tickDelayMS = 0,
            engineId = "engineId",
            traceLogger = EngineTraceLogger("engineId", logFilePath = null, consoleOutput = false),
            featureManager = featureManager
        )
        contexts = engine.runSetup()
    }

    @Benchmark
    fun notSupported() {
        engine.runLoop(contexts, mutableMapOf()) {  }
    }
}

class TickLimitOverheadSpecializedSupported(): BenchmarkSettings() {
    lateinit var engine: Engine

    lateinit var contexts: List<SMContext>

    val featureManager: FeatureManager = FeatureManager()

    val globalMetamodelId = "TickLimitModel"

    val globalModelElementId = "tickLimitModel"

    val metamodel = KmetaDSLConverter.parseKmetaString("type \"$globalMetamodelId\" \"custom state machine supporting tick limit\" {}".trimIndent())

    val modelDefinition = "make object \"$globalModelElementId\": \"$globalMetamodelId\" {}".trimIndent()

    val statemachineMap = mapOf(Pair(globalModelElementId, KstatesDSLConverter.parseKstatesString("STATEMACHINE ATTACHED TO \"$globalMetamodelId\" {\n" +
            "\tSTATES {\n" +
            "\t\tSTATE \"delayTest\" {\n" +
            "\t\t\tTICK LIMIT {\n" +
            "\t\t\t\tDELAY: 5 TICKS\n" +
            "\t\t\t}\n" +
            "\t\t\tINITIAL STATE \"waitForDelay\" {}\n" +
            "\t\t}\n" +
            "\t\tSTATE \"timeoutTest\" {\n" +
            "\t\t\tTICK LIMIT {\n" +
            "\t\t\t\tTIMEOUT: AFTER 5 TICKS TRANSITION TO \"waitForDelay\"\n" +
            "\t\t\t}\n" +
            "\t\t\tSTATE \"waitForTimeout\" {}\n" +
            "\t\t}\n" +
            "\t}\n" +
            "\t\n" +
            "\tTRANSITIONS {\n" +
            "\t\tTRANSITION \"waitForDelay\" -> \"waitForTimeout\" {}\n" +
            "\t}\n" +
            "}".trimIndent())))

    @Setup(Level.Invocation)
    fun setup() {
        engine = Engine(
            metamodel = metamodel,
            model = KmodelDSLConverter.parseKmodelString(modelDefinition, metamodel),
            statemachineMap = statemachineMap,
            tickDelayMS = 0,
            engineId = "engineId",
            traceLogger = EngineTraceLogger("engineId", logFilePath = null, consoleOutput = false),
            featureManager = featureManager
        )
        contexts = engine.runSetup()
    }

    @Setup
    fun featureActivation() {
        this.featureManager.requestFeatureActivation(TickLimitFeature::class)
    }

    @TearDown
    fun featureDeactivation() {
        this.featureManager.requestFeatureDeactivation(TickLimitFeature::class)
    }

    @Benchmark
    fun supported() {
        engine.runLoop(contexts, mutableMapOf()) {  }
    }
}