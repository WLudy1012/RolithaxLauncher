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
internal const val MAX_SIGNALR_MESSAGE = 1024 * 1024
private const val MAX_SIGNALR_MESSAGES_PER_FRAME = 4096

internal class SignalRFrames(private val maxMessageSize: Int = MAX_SIGNALR_MESSAGE) {
    private val pending = StringBuilder()

    fun append(text: String): List<String> {
        pending.append(text)
        val messages = mutableListOf<String>()
        var start = 0
        while (true) {
            val separator = pending.indexOf(SIGNALR_SEPARATOR, start)
            if (separator < 0) break
            require(separator - start <= maxMessageSize) { "SignalR 消息过大" }
            require(messages.size < MAX_SIGNALR_MESSAGES_PER_FRAME) { "SignalR 消息过多" }
            messages += pending.substring(start, separator)
            start = separator + 1
        }
        pending.deleteRange(0, start)
        require(pending.length <= maxMessageSize) { "SignalR 消息过大" }
        return messages
    }
}

internal fun signalRInvocation(target: String, arguments: List<JsonElement>, invocationId: String? = null): String {
    val message = buildJsonObject {
        put("type", 1)
        if (invocationId != null) put("invocationId", invocationId)
        put("target", target)
        put("arguments", JsonArray(arguments))
    }
    return message.toString() + SIGNALR_SEPARATOR
}

internal fun signalRObject(text: String): JsonObject = Json.parseToJsonElement(text) as? JsonObject
    ?: throw IllegalArgumentException("SignalR 消息格式无效")
