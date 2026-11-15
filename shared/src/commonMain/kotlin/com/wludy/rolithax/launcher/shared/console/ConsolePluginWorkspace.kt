package com.wludy.rolithax.launcher.shared.console

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ConsolePluginWorkspace {
    private val session = SchemaSession()

    fun updateLog(text: String): Boolean = session.request(buildJsonObject {
        put("op", "log")
        put("text", text)
    }).jsonPrimitive.boolean

    fun markGap(): Boolean = operation("gap").jsonPrimitive.boolean
    fun reset(): Boolean = operation("reset").jsonPrimitive.boolean
    fun snapshot(): ConsolePanelSnapshot = schemaJson.decodeFromJsonElement(operation("snapshot"))
    fun restoreBuiltIn(): ConsolePanelSnapshot = schemaJson.decodeFromJsonElement(operation("restore"))

    fun command(actionId: String, arguments: Map<String, String> = emptyMap(), expectedRevision: Long? = null): String? =
        session.request(buildJsonObject {
            put("op", "command")
            put("actionId", actionId)
            put("arguments", schemaJson.encodeToJsonElement(arguments))
            expectedRevision?.let { put("expectedRevision", it) }
        }).jsonPrimitive.contentOrNull

    fun reload(source: ConsolePluginSource, allowedCommands: Set<String> = emptySet()): ConsolePanelSnapshot =
        schemaJson.decodeFromJsonElement(session.request(buildJsonObject {
            put("op", "reload")
            put("source", schemaJson.encodeToJsonElement(source))
            put("allowedCommands", schemaJson.encodeToJsonElement(allowedCommands))
        }))

    fun close() = session.close()
    private fun operation(name: String) = session.request(buildJsonObject { put("op", JsonPrimitive(name)) })
}
