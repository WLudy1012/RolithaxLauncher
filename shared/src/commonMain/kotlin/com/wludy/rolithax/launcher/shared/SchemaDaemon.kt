package com.wludy.rolithax.launcher.shared

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SchemaRoute = "api/plugin/mslx-plugin-rolithax/schema"

suspend fun DaemonClient.schemaCapabilities(): JsonElement? = try {
    get("$SchemaRoute/capabilities")
} catch (error: DaemonException) {
    if (error.httpStatus == 404 || error.businessCode == 404) null else throw error
}

suspend fun DaemonClient.schemaLayout(id: Long): JsonElement? = get("$SchemaRoute/instances/$id/layout")

suspend fun DaemonClient.schemaPreflight(
    id: Long,
    kind: String,
    target: String,
    commandProbeId: String? = null,
): JsonElement? {
    require(kind in setOf("mod", "minecraftPlugin", "mcdrPlugin"))
    require(Regex("[a-zA-Z0-9_.-]{1,128}").matches(target))
    require(commandProbeId == null || Regex("probe-[0-9a-f]{32}").matches(commandProbeId))
    return post("$SchemaRoute/instances/$id/preflight", buildJsonObject {
        put("kind", kind)
        put("target", target)
        commandProbeId?.let { put("commandProbeId", it) }
    }.toString())
}

suspend fun DaemonClient.schemaTasks(id: Long): JsonElement? = get("$SchemaRoute/instances/$id/tasks")

suspend fun DaemonClient.schemaRunTask(id: Long, taskId: String): JsonElement? {
    require(Regex("[a-zA-Z0-9_-]{1,64}").matches(taskId))
    return post("$SchemaRoute/instances/$id/tasks/$taskId/run", "{}")
}
