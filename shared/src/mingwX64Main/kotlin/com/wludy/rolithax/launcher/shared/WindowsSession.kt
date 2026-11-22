package com.wludy.rolithax.launcher.shared

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal interface WindowsSession {
    suspend fun call(operation: String, args: JsonObject): JsonElement?
    suspend fun close()
    fun redact(message: String): String
}
