package com.wludy.rolithax.launcher.shared.console

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal expect object SchemaNativeBridge {
    fun create(): Long
    fun destroy(handle: Long)
    fun request(handle: Long, bytes: ByteArray): ByteArray
}

internal val schemaJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal class SchemaSession {
    private var handle = SchemaNativeBridge.create().also { check(it != 0L) { "Rolithax Schema 初始化失败" } }

    fun request(request: JsonObject): JsonElement {
        check(handle != 0L) { "Rolithax Schema 会话已关闭" }
        val response = schemaJson.parseToJsonElement(
            SchemaNativeBridge.request(handle, request.toString().encodeToByteArray()).decodeToString(throwOnInvalidSequence = true),
        ).jsonObject
        if (response["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            val error = response["error"]?.jsonObject
            throw ConsolePluginException(
                error?.get("file")?.jsonPrimitive?.contentOrNull ?: "native",
                error?.get("line")?.jsonPrimitive?.intOrNull ?: 1,
                error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Rolithax Schema 请求失败",
            )
        }
        return requireNotNull(response["data"]) { "Rolithax Schema 返回了空响应" }
    }

    fun close() {
        val previous = handle
        handle = 0
        if (previous != 0L) SchemaNativeBridge.destroy(previous)
    }
}
