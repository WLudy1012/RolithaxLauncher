package com.wludy.rolithax.launcher.shared.console

import kotlinx.serialization.Serializable

@Serializable
data class ConsolePluginSource(val manifest: String, val ui: String, val rules: String)

@Serializable
data class ConsolePanelNode(
    val id: String,
    val kind: String,
    val label: String,
    val field: String? = null,
    val action: String? = null,
    val children: List<ConsolePanelNode> = emptyList(),
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

internal fun invalid(file: String, line: Int, reason: String): Nothing =
    throw ConsolePluginException(file, line, reason)

internal val symbol = Regex("[a-z][a-z0-9-]{0,63}")

class CompiledConsolePlugin internal constructor(
    val id: String,
    val version: String,
    val server: String,
    val title: String,
    val nodes: List<ConsolePanelNode>,
    internal val initial: Map<String, String>,
    internal val rules: List<LogRule>,
    internal val commandSpecs: Map<String, CommandSpec>,
) {
    val commands: Map<String, String> get() = commandSpecs.mapValues { it.value.text }
    val commandIds: Set<String> get() = commandSpecs.keys.toSet()
}

internal class LogRule(
    val prefix: String,
    private val suffix: String,
    private val contains: String,
    val field: String,
    val operation: String,
    private val value: String?,
    private val capture: String?,
) {
    fun match(line: String): String? {
        if (line.length < prefix.length + suffix.length ||
            !line.startsWith(prefix) || !line.endsWith(suffix) || !line.contains(contains)
        ) return null
        if (value != null) return value
        val middle = line.substring(prefix.length, line.length - suffix.length)
        return when (capture) {
            "player" -> middle.takeIf(::isPlayer)
            "players" -> {
                players(middle)?.joinToString("\n")
            }
            "player-event" -> line.takeIf { isPlayer(middle) }
            "online", "online-summary" -> {
                val result = onlineLine.matchEntire(middle) ?: return null
                val players = players(result.groupValues[2]) ?: return null
                if (players.size != result.groupValues[1].toInt()) return null
                if (capture == "online") players.joinToString("\n") else "${players.size} 人在线"
            }
            else -> middle.take(512)
        }
    }

    private fun players(value: String): List<String>? =
        if (value.isEmpty()) emptyList()
        else value.split(", ", limit = 65).takeIf { it.size <= 64 && it.all(::isPlayer) }

    companion object {
        private val onlineLine = Regex("""([0-9]{1,2}) of a max of [0-9]{1,9} players online: ?(.*)""")
    }
}

internal fun isPlayer(value: String): Boolean =
    value.length in 1..16 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }

internal class CommandSpec(val text: String, val parameter: String?) {
    fun resolve(arguments: Map<String, String>): String? {
        if (parameter == null) return text.takeIf { arguments.isEmpty() }
        val value = arguments[parameter]?.takeIf(::isPlayer) ?: return null
        return text.replace("{$parameter}", value).takeIf { arguments.size == 1 }
    }
}
