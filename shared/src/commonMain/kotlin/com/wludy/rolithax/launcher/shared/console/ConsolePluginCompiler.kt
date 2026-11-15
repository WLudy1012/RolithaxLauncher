package com.wludy.rolithax.launcher.shared.console

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

object ConsolePluginCompiler {
    val paperSource: ConsolePluginSource get() = paperPluginSource

    fun compile(source: ConsolePluginSource): CompiledConsolePlugin {
        val session = SchemaSession()
        return try {
            schemaJson.decodeFromJsonElement(session.request(buildJsonObject {
                put("op", "inspect")
                put("source", schemaJson.encodeToJsonElement(source))
            }))
        } finally {
            session.close()
        }
    }
}
