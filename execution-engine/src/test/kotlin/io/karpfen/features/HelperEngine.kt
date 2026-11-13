package io.karpfen.features

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import dsl.textual.KstatesDSLConverter
import io.karpfen.Engine
import io.karpfen.EngineTraceLogger
import io.karpfen.io.karpfen.features.FeatureManager
import io.karpfen.io.karpfen.messages.Event
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

val regex = "#(\\d+)".toRegex()

fun createEngine(stateMachineDefinition: String, featureManager: FeatureManager): Engine {
    val metamodel = KmetaDSLConverter.parseKmetaString(testMetaModel)
    return Engine(
        metamodel = metamodel,
        model = KmodelDSLConverter.parseKmodelString(testModel, metamodel),
        statemachineMap = mapOf(Pair(globalModelElementId, KstatesDSLConverter.parseKstatesString(stateMachineDefinition))),
        tickDelayMS = 10,
        engineId = "engineId",
        traceLogger = EngineTraceLogger("engineId", consoleOutput = true),
        featureManager = featureManager
    )
}

fun stopEngine(engine: Engine) {
    //quick cancel to speed up test
    val isRunningField = Engine::class.java.getDeclaredField("isRunning")
    isRunningField.setAccessible(true)
    isRunningField.set(engine, false)
    val threadField = Engine::class.java.getDeclaredField("executionThread")
    threadField.setAccessible(true)
    val thread = threadField.get(engine) as Thread
    thread.interrupt()
}

fun runEngineCycle(engine: Engine, stop: Boolean) {
    engine.start()
    //Wait long enough to prevent race conditions
    Thread.sleep(200)
    if (stop) stopEngine(engine)
}

//create new engine and threads to prevent race conditions
fun createEngineAndRunEngineCycle(stateMachineDefinition: String, featureManager: FeatureManager, stop: Boolean): Engine {
    val engine = createEngine(stateMachineDefinition, featureManager)
    runEngineCycle(engine, stop)
    return engine
}

fun getLastEnteredState(traceLogger: EngineTraceLogger): String {
    return traceLogger.tracesOfType(EngineTraceLogger.TraceEventType.STATE_ENTRY_SKIP).last().details["state"]!!
}

fun getCurrentState(traceLogger: EngineTraceLogger): String {
    return traceLogger.tracesOfType(EngineTraceLogger.TraceEventType.TICK_END).last().details["currentStates"]!!
}

fun getCurrentTickNumber(traceLogger: EngineTraceLogger): Int? {
    val tickNumber = regex.find(traceLogger.tracesOfType(EngineTraceLogger.TraceEventType.TICK_START).last().message)
    return if (tickNumber != null) {
        tickNumber.groupValues[1].toInt()
    } else null
}

fun getAllTickStartDetails(traceLogger: EngineTraceLogger): MutableMap<Int, Map<String, String>> {
    val out = mutableMapOf<Int, Map<String, String>>()
    traceLogger.tracesOfType(EngineTraceLogger.TraceEventType.TICK_START).forEach {
        val tickNumber = regex.find(it.message)
        if (tickNumber != null) {
            out[tickNumber.groupValues[1].toInt()] = it.details
        }
    }
    return out
}

fun getAllTickEndDetails(traceLogger: EngineTraceLogger): MutableMap<Int, Map<String, String>> {
    val out = mutableMapOf<Int, Map<String, String>>()
    traceLogger.tracesOfType(EngineTraceLogger.TraceEventType.TICK_END).forEach {
        val tickNumber = regex.find(it.message)
        if (tickNumber != null) {
            out[tickNumber.groupValues[1].toInt()] = it.details
        }
    }
    return out
}

fun getTickStartDetails(traceLogger: EngineTraceLogger, tick: Int): Map<String, String>? {
    return traceLogger.tracesOfType(EngineTraceLogger.TraceEventType.TICK_START).filter {
        val tickNumber = regex.find(it.message)
        if (tickNumber != null) {
            return@filter tickNumber.groupValues[1].toInt() == tick
        }
        false
    }.lastOrNull()?.details
}

fun getTickEndDetails(traceLogger: EngineTraceLogger, tick: Int): Map<String, String>? {
    return traceLogger.tracesOfType(EngineTraceLogger.TraceEventType.TICK_END).filter {
        val tickNumber = regex.find(it.message)
        if (tickNumber != null) {
            return@filter tickNumber.groupValues[1].toInt() == tick
        }
        false
    }.lastOrNull()?.details
}

fun getAllTransitionsWithTicks(traceLogger: EngineTraceLogger): MutableMap<Int, Map<String, String>> {
    val out = mutableMapOf<Int, Map<String, String>>()
    val startTicks = getAllTickStartDetails(traceLogger)
    for ((endTick, details) in getAllTickEndDetails(traceLogger)) {
        if (startTicks[endTick]?.get("stack") != details["stack"]) {
            out[endTick] = mutableMapOf(Pair("from", details["stack"]!!), Pair("to", startTicks[endTick]?.get("stack")!!))
        }
    }
    return out
}

fun getLastFiredTransitionDetails(traceLogger: EngineTraceLogger): Map<String, String>? {
    return traceLogger.tracesOfType(EngineTraceLogger.TraceEventType.TRANSITION_FIRED).lastOrNull()?.details
}

fun getLastFiredSplitDetails(traceLogger: EngineTraceLogger): Map<String, String>? {
    return traceLogger.tracesOfType(EngineTraceLogger.TraceEventType.SPLIT_FIRED).lastOrNull()?.details
}

fun getLastFiredJoinDetails(traceLogger: EngineTraceLogger): Map<String, String>? {
    return traceLogger.tracesOfType(EngineTraceLogger.TraceEventType.JOIN_FIRED).lastOrNull()?.details
}

fun compareEvent(event1: Event, event2: Event) {
    assertEquals(event1.domain, event2.domain)
    assertEquals(event1.name, event2.name)
    assertEquals(event1.payload, event2.payload)
    assertEquals(event1.payloadFormat, event2.payloadFormat)
}

fun assertStateReached(stateName: String, engine: Engine) {
    assertEquals(stateName, getLastEnteredState(engine.traceLogger!!))
}

fun assertTransitionHappenedAt(engine: Engine, tick: Int, startStack: String, endStack: String) {
    var details = assertNotNull(getTickStartDetails(engine.traceLogger!!, tick))
    assertEquals(startStack, details["stack"])
    details = assertNotNull(getTickEndDetails(engine.traceLogger, tick))
    assertEquals(endStack, details["stack"])
}