package com.wludy.rolithax.launcher.shared.console

internal class RuleYaml(private val text: String) {
    private data class Entry(val line: Int, val values: MutableMap<String, String> = linkedMapOf())
    data class Result(
        val initial: Map<String, String>,
        val rules: List<LogRule>,
        val commands: Map<String, CommandSpec>,
    )

    fun parse(): Result {
        if (text.length > 65_536) fail(1, "Rules are too large")
        val sections = linkedMapOf<String, MutableMap<String, Entry>>()
        var section = ""
        var entry: Entry? = null
        for ((index, raw) in text.lineSequence().withIndex()) {
            val line = index + 1
            val content = raw.trimEnd()
            if (content.isBlank() || content.trimStart().startsWith('#')) continue
            val indent = content.indexOfFirst { it != ' ' }
            val colon = content.indexOf(':', indent)
            if (colon < 0) fail(line, "Expected a mapping entry")
            val key = content.substring(indent, colon)
            if (!symbol.matches(key)) fail(line, "Invalid mapping key")
            val value = content.substring(colon + 1).trimStart()
            when (indent) {
                0 -> {
                    if (key !in setOf("initial", "rules", "commands") || value.isNotEmpty() || key in sections)
                        fail(line, "Unknown or duplicate section")
                    sections[key] = linkedMapOf()
                    section = key
                    entry = null
                }
                2 -> {
                    val entries = sections[section] ?: fail(line, "Missing section")
                    if (entries.size >= 128 || key in entries) fail(line, "Too many or duplicate entries")
                    val next = Entry(line)
                    entries[key] = next
                    if (section == "initial") next.values["value"] = scalar(value, line)
                    else if (value.isNotEmpty()) fail(line, "Expected a nested mapping")
                    entry = next
                }
                4 -> {
                    val values = entry?.values ?: fail(line, "Missing rule or command ID")
                    if (section == "initial" || key in values || values.size >= 8)
                        fail(line, "Unexpected or duplicate property")
                    values[key] = scalar(value, line)
                }
                else -> fail(line, "Use two-space indentation")
            }
        }
        val initial = sections["initial"].orEmpty().mapValues { it.value.values.getValue("value") }
        val rules = sections["rules"].orEmpty().values.map(::rule)
        val commands = sections["commands"].orEmpty().mapValues { command(it.value) }
        return Result(initial, rules, commands)
    }

    private fun rule(entry: Entry): LogRule {
        val properties = entry.values
        val allowed = setOf("prefix", "suffix", "contains", "field", "operation", "value", "capture")
        if (!allowed.containsAll(properties.keys)) fail(entry.line, "Unknown rule property")
        val prefix = properties["prefix"].orEmpty()
        val suffix = properties["suffix"].orEmpty()
        val contains = properties["contains"].orEmpty()
        if (prefix.isEmpty() && suffix.isEmpty() && contains.isEmpty())
            fail(entry.line, "A prefix, suffix or contains matcher is required")
        val field = properties["field"]?.takeIf(symbol::matches) ?: fail(entry.line, "Invalid state field")
        val operation = properties["operation"] ?: "set"
        val value = properties["value"]
        val capture = properties["capture"]
        if (operation !in setOf("set", "append") || (value == null) == (capture == null) ||
            capture != null && capture !in setOf("text", "player", "players", "player-event", "online", "online-summary")
        ) fail(entry.line, "Expected a valid operation and either value or capture")
        return LogRule(prefix, suffix, contains, field, operation, value, capture)
    }

    private fun command(entry: Entry): CommandSpec {
        val properties = entry.values
        if (!setOf("text", "player").containsAll(properties.keys)) fail(entry.line, "Unknown command property")
        val template = properties["text"] ?: fail(entry.line, "Expected command text")
        val player = properties["player"]
        if (player != null && !symbol.matches(player)) fail(entry.line, "Invalid command input name")
        val parts = template.split(' ')
        if (template.length !in 1..160 || !commandName.matches(parts.first()) ||
            parts.drop(1).any { it != "{$player}" || player == null } ||
            parts.size != (if (player == null) 1 else 2)
        ) fail(entry.line, "Use a command name and at most one player placeholder")
        return CommandSpec(template, player)
    }

    private fun scalar(value: String, line: Int): String =
        readString(value, "rules.yaml", line).also { result ->
            if (result.length > 512 || result.any(Char::isISOControl))
                fail(line, "Scalar is too long or contains control characters")
        }

    private fun fail(line: Int, reason: String): Nothing = invalid("rules.yaml", line, reason)

    companion object {
        private val commandName = Regex("[a-zA-Z0-9][a-zA-Z0-9_:-]{0,63}")
    }
}
