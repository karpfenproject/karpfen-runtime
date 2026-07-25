package io.karpfen.features

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import dsl.textual.KstatesDSLConverter
import io.karpfen.Engine
import io.karpfen.EngineTraceLogger
import io.karpfen.io.karpfen.exec.SMContext
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.messages.Event
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.coroutines.DelicateCoroutinesApi
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.State

@State(Scope.Benchmark)
abstract class ComplexStateMachineBenchmark {
    lateinit var engine: Engine

    lateinit var contexts: List<SMContext>

    val globalMetamodelId = "DrivingModel"

    val globalModelElementId = "drivingModel"

    val metamodel = KmetaDSLConverter.parseKmetaString("type \"$globalMetamodelId\" \"advanced Statemachine for benchmarking\" {\n" +
            "\tprop(\"driving\", \"boolean\")\n" +
            "\tprop(\"speed\", \"number\")\n" +
            "}".trimIndent())

    val modelDefinition = "make object \"$globalModelElementId\": \"$globalMetamodelId\" {\n" +
            "\tprop(\"driving\") -> \"true\"\n" +
            "\tprop(\"speed\") -> \"0\"\n" +
            "}".trimIndent()

    val statemachineMap = mapOf(Pair(globalModelElementId, KstatesDSLConverter.parseKstatesString("STATEMACHINE ATTACHED TO \"DrivingModel\" {\n" +
            "\tSTATES {\n" +
            "\t\tSTATE \"driving\" {\n" +
            "\t\t\tENTRY {\n" +
            "\t\t\t\tSET(\"driving\", \"true\")\n" +
            "\t\t\t}\n" +
            "\t\t\tINITIAL STATE \"accelerate\" {\n" +
            "\t\t\t\tDO {\n" +
            "\t\t\t\t\tSET(\"speed\", MACRO(\"accelerate\", \"speed\"))\n" +
            "\t\t\t\t}\n" +
            "\t\t\t}\n" +
            "\t\t\tSTATE \"isMaxSpeed\" {}\n" +
            "\t\t}\n" +
            "\t\tSTATE \"slowing down\" {\n" +
            "\t\t\tSTATE \"slow down\" {\n" +
            "\t\t\t\tDO {\n" +
            "\t\t\t\t\tSET(\"speed\", MACRO(\"brake\", \"speed\"))\n" +
            "\t\t\t\t}\n" +
            "\t\t\t}\n" +
            "\t\t\tSTATE \"isStopped\" {}\n" +
            "\t\t}\n" +
            "\t\tSTATE \"waiting at traffic light\" {\n" +
            "\t\t\tENTRY {\n" +
            "\t\t\t\tSET(\"driving\", \"false\")\n" +
            "\t\t\t\tSET(\"speed\", \"0\")\n" +
            "\t\t\t}\n" +
            "\t\t\tSTATE \"traffic light state\" {\n" +
            "\t\t\t\tSTATE \"turningGreen\" {\n" +
            "\t\t\t\t\tSTATE \"turningGreenIsRed\" {}\n" +
            "\t\t\t\t\tSTATE \"turningGreenIsYellow\" {}\n" +
            "\t\t\t\t}\n" +
            "\t\t\t\tSTATE \"turningRed\" {\t\t\t\t\t\n" +
            "\t\t\t\t\tSTATE \"turningRedIsGreen\" {}\n" +
            "\t\t\t\t\tSTATE \"turningRedIsYellow\" {}\n" +
            "\t\t\t\t}\n" +
            "\t\t\t}\n" +
            "\t\t\tSTATE \"pedestrian state\" {\n" +
            "\t\t\t\tSTATE \"not present\" {}\n" +
            "\t\t\t\tSTATE \"about to cross\" {}\n" +
            "\t\t\t\tSTATE \"crossing\" {}\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t}\n" +
            "\t\n" +
            "\tTRANSITIONS {\n" +
            "\t\tTRANSITION \"isMaxSpeed\" -> \"isStopped\" {\n" +
            "\t\t\tCONDITION {\n" +
            "\t\t\t\tEVENT(\"domain\", \"traffic light spotted\")\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t\tTRANSITION \"accelerate\" -> \"isStopped\" {\n" +
            "\t\t\tCONDITION {\n" +
            "\t\t\t\tEVENT(\"domain\", \"traffic light spotted\")\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t\tTRANSITION \"accelerate\" -> \"isMaxSpeed\" {}\n" +
            "\t\tTRANSITION \"isMaxSpeed\" -> \"accelerate\" {\n" +
            "\t\t\tCONDITION {\n" +
            "\t\t\t\tEVAL { return \$(speed) < 50 }\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t\tTRANSITION \"slow down\" -> \"isStopped\" {}\n" +
            "\t\tTRANSITION \"isStopped\" -> \"slow down\" {\n" +
            "\t\t\tCONDITION {\n" +
            "\t\t\t\tEVAL { return \$(speed) > 0 }\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t\tSPLIT \"isStopped\" -> \"turningGreenIsRed\", \"not present\" {}\n" +
            "\t\tJOIN \"turningRedIsGreen\", \"not present\" -> \"accelerate\" {}\n" +
            "\t\tTRANSITION \"turningGreenIsRed\" -> \"turningGreenIsYellow\" {}\n" +
            "\t\tTRANSITION \"turningGreenIsYellow\" -> \"turningRedIsGreen\" {}\n" +
            "\t\tTRANSITION \"turningRedIsGreen\" -> \"turningRedIsYellow\" {}\n" +
            "\t\tTRANSITION \"turningRedIsYellow\" -> \"turningGreenIsRed\" {}\n" +
            "\t\tTRANSITION \"not present\" -> \"about to cross\" {}\n" +
            "\t\tTRANSITION \"about to cross\" -> \"crossing\" {}\n" +
            "\t\tTRANSITION \"crossing\" -> \"not present\" {}\n" +
            "\t}\n" +
            "\t\n" +
            "\tMACROS {\n" +
            "\t\tMACRO \"accelerate\" {\n" +
            "\t\t\tTAKES(\"currentSpeed\", \"number\")\n" +
            "\t\t\tRETURNS(\"number\")\n" +
            "\t\t\tDEFINITION {\n" +
            "\t\t\t\tEVAL {\n" +
            "\t\t\t\t\treturn (\$(currentSpeed) + 1)\n" +
            "\t\t\t\t}\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t\tMACRO \"brake\" {\n" +
            "\t\t\tTAKES(\"currentSpeed\", \"number\")\n" +
            "\t\t\tRETURNS(\"number\")\n" +
            "\t\t\tDEFINITION {\n" +
            "\t\t\t\tEVAL {\n" +
            "\t\t\t\t\treturn (\$(currentSpeed) - 5)\n" +
            "\t\t\t\t}\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t}\n" +
            "}".trimIndent())))

    @OptIn(DelicateCoroutinesApi::class)
    @Setup(Level.Invocation)
    fun setup() {
        engine = Engine(
            metamodel = metamodel,
            model = KmodelDSLConverter.parseKmodelString(modelDefinition, metamodel),
            statemachineMap = statemachineMap,
            tickDelayMS = 0,
            engineId = "engineId",
            traceLogger = EngineTraceLogger("engineId", logFilePath = null, consoleOutput = false),
            featureManager = FeatureManager()
        )
        contexts = engine.runSetup()
    }

    fun startBenchmark() {
        engine.runLoop(contexts, mutableMapOf()) { tickCount ->
            //inject a predictable amount of events
            if (tickCount % 30L == 5L) {
                engine.receiveExternalEvent(Event("domain", "traffic light spotted", ttlMs = 1))
            }
            if (tickCount % 11L == 0L) {
                engine.receiveExternalEvent(Event("domain", "irrelevant", ttlMs = 3))
            }
            if (tickCount % 7L == 0L) {
                engine.receiveExternalEvent(Event("domain", "irrelevant", ttlMs = 5))
            }
        }
    }
}