package io.karpfen.features

const val globalMetamodelId = "TestMachine"

const val globalModelElementId = "testMachine"

val testMetaModel = "type \"$globalMetamodelId\" \"Machine for testing\" {prop(\"state\", \"boolean\")}".trimIndent()

val testModel = "make object \"$globalModelElementId\":\"$globalMetamodelId\" {prop(\"state\") -> \"true\"}".trimIndent()