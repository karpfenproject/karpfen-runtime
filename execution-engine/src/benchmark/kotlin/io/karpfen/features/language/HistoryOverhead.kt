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
import io.karpfen.io.karpfen.features.language.HistoryFeature
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Setup
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Level

class HistoryOverhead: ComplexStateMachineBenchmark() {

    @Setup
    fun featureActivation() {
        this.featureManager.requestFeatureActivation(HistoryFeature::class)
    }

    @TearDown
    fun featureDeactivation() {
        this.featureManager.requestFeatureDeactivation(HistoryFeature::class)
    }

    @Benchmark
    fun idle() {
        this.startBenchmark()
    }
}

class HistoryOverheadSpecializedNotSupported: BenchmarkSettings() {
    lateinit var engine: Engine

    lateinit var contexts: List<SMContext>

    val featureManager: FeatureManager = FeatureManager()

    val globalMetamodelId = "HistoryModel"

    val globalModelElementId = "historyModel"

    val metamodel = KmetaDSLConverter.parseKmetaString("type \"$globalMetamodelId\" \"custom state machine not supporting history\" {\n" +
            "\tprop(\"shallowHistory\", \"number\")\n" +
            "\tprop(\"deepHistory\", \"number\")\n" +
            "}".trimIndent())

    val modelDefinition = "make object \"$globalModelElementId\": \"$globalMetamodelId\" {\n" +
            "\tprop(\"shallowHistory\") -> \"0\"\n" +
            "\tprop(\"deepHistory\") -> \"0\"\n" +
            "}".trimIndent()

    val statemachineMap = mapOf(Pair(globalModelElementId, KstatesDSLConverter.parseKstatesString("STATEMACHINE ATTACHED TO \"$globalMetamodelId\" {\n" +
            "\tSTATES {\n" +
            "\t\tINITIAL STATE \"noHistory\" {}\n" +
            "\t\tSTATE \"shallowHistory\" {\n" +
            "\t\t\tSTATE \"middleShallowHistory\" {\n" +
            "\t\t\t\tENTRY {\n" +
            "\t\t\t\t\tSET(\"shallowHistory\", \"1\")\n" +
            "\t\t\t\t}\n" +
            "\t\t\t\tSTATE \"innerShallowHistory\" {}\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t\tSTATE \"deepHistory\" {\n" +
            "\t\t\tSTATE \"middleDeepHistory\" {\n" +
            "\t\t\t\tSTATE \"innerDeepHistory\" {\n" +
            "\t\t\t\t\tENTRY {\n" +
            "\t\t\t\t\t\tSET(\"deepHistory\", \"1\")\n" +
            "\t\t\t\t\t}\n" +
            "\t\t\t\t}\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t}\n" +
            "\t\n" +
            "\tTRANSITIONS {\n" +
            "\t\tTRANSITION \"noHistory\" -> \"middleShallowHistory\" {\n" +
            "\t\t\tCONDITION {\n" +
            "\t\t\t\tEVAL { return $(shallowHistory) == 1 }\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t\tTRANSITION \"noHistory\" -> \"shallowHistory\" {}\n" +
            "\t\tTRANSITION \"shallowHistory\" -> \"middleShallowHistory\" {}\n" +
            "\t\tTRANSITION \"middleShallowHistory\" -> \"innerShallowHistory\" {}\n" +
            "\t\tTRANSITION \"innerShallowHistory\" -> \"innerDeepHistory\" {\n" +
            "\t\t\tCONDITION {\n" +
            "\t\t\t\tEVAL { return $(deepHistory) == 1 }\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t\tTRANSITION \"innerShallowHistory\" -> \"deepHistory\" {}\n" +
            "\t\tTRANSITION \"deepHistory\" -> \"middleDeepHistory\" {}\n" +
            "\t\tTRANSITION \"middleDeepHistory\" -> \"innerDeepHistory\" {}\n" +
            "\t\tTRANSITION \"innerDeepHistory\" -> \"noHistory\" {}\n" +
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
        this.featureManager.requestFeatureActivation(HistoryFeature::class)
    }

    @TearDown
    fun featureDeactivation() {
        this.featureManager.requestFeatureDeactivation(HistoryFeature::class)
    }

    @Benchmark
    fun notSupported() {
        engine.runLoop(contexts, mutableMapOf()) {  }
    }
}

class HistoryOverheadSpecializedSupported: BenchmarkSettings() {
    lateinit var engine: Engine

    lateinit var contexts: List<SMContext>

    val featureManager: FeatureManager = FeatureManager()

    val globalMetamodelId = "HistoryModel"

    val globalModelElementId = "historyModel"

    val metamodel = KmetaDSLConverter.parseKmetaString("type \"$globalMetamodelId\" \"custom state machine supporting history\" {}".trimIndent())

    val modelDefinition = "make object \"$globalModelElementId\": \"$globalMetamodelId\" {}".trimIndent()

    val statemachineMap = mapOf(Pair(globalModelElementId, KstatesDSLConverter.parseKstatesString("STATEMACHINE ATTACHED TO \"$globalMetamodelId\" {\n" +
            "\tSTATES {\n" +
            "\t\tINITIAL STATE \"noHistory\" {}\n" +
            "\t\tSHALLOW HISTORY STATE \"shallowHistory\" {\n" +
            "\t\t\tSTATE \"middleShallowHistory\" {\n" +
            "\t\t\t\tSTATE \"innerShallowHistory\" {}\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t\tDEEP HISTORY STATE \"deepHistory\" {\n" +
            "\t\t\tSTATE \"middleDeepHistory\" {\n" +
            "\t\t\t\tSTATE \"innerDeepHistory\" {}\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t}\n" +
            "\t\n" +
            "\tTRANSITIONS {\n" +
            "\t\tTRANSITION \"noHistory\" -> \"shallowHistory\" {}\n" +
            "\t\tTRANSITION \"shallowHistory\" -> \"middleShallowHistory\" {}\n" +
            "\t\tTRANSITION \"middleShallowHistory\" -> \"innerShallowHistory\" {}\n" +
            "\t\tTRANSITION \"innerShallowHistory\" -> \"deepHistory\" {}\n" +
            "\t\tTRANSITION \"deepHistory\" -> \"middleDeepHistory\" {}\n" +
            "\t\tTRANSITION \"middleDeepHistory\" -> \"innerDeepHistory\" {}\n" +
            "\t\tTRANSITION \"innerDeepHistory\" -> \"noHistory\" {}\n" +
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
        this.featureManager.requestFeatureActivation(HistoryFeature::class)
    }

    @TearDown
    fun featureDeactivation() {
        this.featureManager.requestFeatureDeactivation(HistoryFeature::class)
    }

    @Benchmark
    fun supported() {
        engine.runLoop(contexts, mutableMapOf()) {  }
    }
}