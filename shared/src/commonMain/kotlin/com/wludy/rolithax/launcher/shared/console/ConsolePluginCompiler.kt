package com.wludy.rolithax.launcher.shared.console

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

object ConsolePluginCompiler {
    val paperSource: ConsolePluginSource get() = paperPluginSource

    fun compile(source: ConsolePluginSource): CompiledConsolePlugin {
        val manifest = manifest(source.manifest)
        val layout = PanelXml(source.ui).parse()
        val rules = RuleYaml(source.rules).parse()
        val fields = rules.initial.keys + rules.rules.map { it.field }
        val controls = layout.second.flatten()
        val inputs = controls.filter { it.kind == "field" }.mapNotNull { it.field }.toSet()
        controls.forEach { control ->
            val field = control.field
            val action = control.action
            if (field != null && control.kind != "field" && field !in fields)
                invalid("ui.xml", 1, "Unknown state field '$field'")
            if (action != null && action !in rules.commands)
                invalid("ui.xml", 1, "Unknown action '$action'")
        }
        rules.commands.forEach { (id, spec) ->
            val parameter = spec.parameter
            if (parameter != null && parameter !in inputs)
                invalid("rules.yaml", 1, "Command '$id' requires a matching UI input '$parameter'")
        }
        return CompiledConsolePlugin(
            manifest.getValue("id"), manifest.getValue("version"), manifest.getValue("server"),
            layout.first, layout.second, rules.initial, rules.rules, rules.commands,
        )
    }

    private fun manifest(text: String): Map<String, String> {
        val file = "plugin.json"
        if (text.length > 8_192) invalid(file, 1, "Manifest is too large")
        val fields = setOf("id", "version", "sdkVersion", "server")
        val keys = mutableSetOf<String>()
        var depth = 0
        for (token in jsonTokens.findAll(text)) {
            when (val value = token.value) {
                "{" -> if (++depth > 1) invalid(file, 1, "Expected a flat manifest")
                "}" -> depth--
                "[", "]" -> invalid(file, 1, "Arrays are not allowed")
                else -> if (text.asSequence().drop(token.range.last + 1).firstOrNull { !it.isWhitespace() } == ':') {
                    val key = readString(value, file, 1)
                    if (!keys.add(key)) invalid(file, 1, "Duplicate manifest key")
                }
            }
        }
        val objectValue = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: IllegalArgumentException) {
            invalid(file, 1, "Expected a JSON object")
        }
        if (objectValue.keys != fields) invalid(file, 1, "Expected id, version, sdkVersion and server")
        if (objectValue["sdkVersion"] != JsonPrimitive(1)) invalid(file, 1, "Unsupported SDK version")
        val strings = (fields - "sdkVersion").associateWith {
            val entry = objectValue[it] as? JsonPrimitive
            entry?.takeIf { value -> value.isString }?.content ?: invalid(file, 1, "'$it' must be a string")
        }
        val id = strings.getValue("id")
        if (id.length > 96 || id.count { it == '.' } < 1 || !id.split('.').all(symbol::matches))
            invalid(file, 1, "Use a namespaced plugin ID")
        if (!version.matches(strings.getValue("version"))) invalid(file, 1, "Expected major.minor.patch")
        if (!symbol.matches(strings.getValue("server"))) invalid(file, 1, "Invalid server family")
        return strings
    }

    private val jsonTokens = Regex("""("(?:[^"\\]|\\.)*"|[{}\[\]])""")
    private val version = Regex("""(?:0|[1-9][0-9]{0,8})(?:\.(?:0|[1-9][0-9]{0,8})){2}""")
}

private fun List<ConsolePanelNode>.flatten(): List<ConsolePanelNode> =
    flatMap { listOf(it) + it.children.flatten() }

internal fun readString(token: String, file: String, line: Int): String {
    if (token.length < 2 || token.first() != '"' || token.last() != '"')
        invalid(file, line, "Expected a double-quoted string")
    return try {
        Json.decodeFromString<String>(token)
    } catch (_: IllegalArgumentException) {
        invalid(file, line, "Invalid quoted string")
    }
}
