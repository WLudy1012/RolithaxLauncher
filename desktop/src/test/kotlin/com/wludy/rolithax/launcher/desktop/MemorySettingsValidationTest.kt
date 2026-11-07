package com.wludy.rolithax.launcher.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MemorySettingsValidationTest {
    @Test
    fun acceptsValidAndOptionalMemoryValues() {
        listOf("""{"minM":0,"maxM":0}""", """{"minM":1024,"maxM":4096}""", """{"minM":null}""").forEach {
            assertNull(validateMemorySettings(Json.parseToJsonElement(it).jsonObject))
        }
    }

    @Test
    fun rejectsInvalidNumbersAndReversedRange() {
        listOf("""{"minM":-1}""", """{"minM":2,"maxM":1}""", """{"minM":1.5}""", """{"minM":"1024"}""", """{"maxM":true}""", """{"maxM":2147483648}""").forEach {
            assertNotNull(validateMemorySettings(Json.parseToJsonElement(it).jsonObject))
        }
    }
}
