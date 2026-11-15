package com.wludy.rolithax.launcher.shared.console

import kotlinx.serialization.Serializable

@Serializable
data class ConsolePluginSource(val manifest: String, val ui: String, val rules: String = "", val lua: String = "")

@Serializable
data class ConsolePanelNode(
    val id: String,
    val kind: String,
    val label: String,
    val field: String? = null,
    val action: String? = null,
    val children: List<ConsolePanelNode> = emptyList(),
    val description: String? = null,
    val tone: String? = null,
    val unit: String? = null,
    val placeholder: String? = null,
)

@Serializable
data class ConsolePanelSnapshot(
    val revision: Long,
    val pluginId: String,
    val title: String,
    val stale: Boolean,
    val nodes: List<ConsolePanelNode>,
    val values: Map<String, String>,
    val evidence: Map<String, String>,
    val packageRevision: Long = 0,
)

class ConsolePluginException(val file: String, val line: Int, reason: String) :
    IllegalArgumentException("$file:$line: $reason")

@Serializable
class CompiledConsolePlugin internal constructor(
    val id: String,
    val version: String,
    val server: String,
    val title: String,
    val nodes: List<ConsolePanelNode>,
    internal val initial: Map<String, String>,
    val commands: Map<String, String>,
    val engine: String = "yaml",
    val sdkVersion: Int = 1,
) {
    val commandIds: Set<String> get() = commands.keys
}
