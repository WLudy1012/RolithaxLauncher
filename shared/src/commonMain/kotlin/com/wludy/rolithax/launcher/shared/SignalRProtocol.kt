package com.wludy.rolithax.launcher.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal const val SIGNALR_SEPARATOR = '\u001e'
private const val MAX_SIGNALR_MESSAGE = 1024 * 1024

internal class SignalRFrames {
    private val pending = StringBuilder()

    fun append(text: String): List<String> {
        pending.append(text)
        val messages = mutableListOf<String>()
        while (true) {
            val separator = pending.indexOf(SIGNALR_SEPARATOR)
            if (separator < 0) break
            require(separator <= MAX_SIGNALR_MESSAGE) { "SignalR 消息过大" }
            messages += pending.substring(0, separator)
            pending.deleteRange(0, separator + 1)
        }
        require(pending.length <= MAX_SIGNALR_MESSAGE) { "SignalR 消息过大" }
        return messages
    }
}

internal fun signalRInvocation(target: String, arguments: List<JsonElement>): String {
    val message = buildJsonObject {
        put("type", 1)
        put("target", target)
        put("arguments", JsonArray(arguments))
    }
    return message.toString() + SIGNALR_SEPARATOR
}

internal fun signalRObject(text: String): JsonObject = Json.parseToJsonElement(text) as? JsonObject
    ?: throw IllegalArgumentException("SignalR 消息格式无效")
