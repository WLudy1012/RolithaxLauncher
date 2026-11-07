package com.wludy.rolithax.launcher.shared.console

/** Owned by one instance session. Calls are serialized by the host. */
class ConsolePluginWorkspace {
    private var model = Model(builtIn, builtIn.commandIds)
    private val history = ArrayDeque<String>()
    private var historyChars = 0
    private var revision = 0L
    private var packageRevision = 0L
    private var stale = false

    fun updateLog(text: String): Boolean {
        if (text.length > 8_192) return markGap()
        val clean = text.trimEnd('\r', '\n')
        if (clean.isBlank() || clean.any { it == '\r' || it == '\n' }) return false
        history.addLast(clean)
        historyChars += clean.length
        while (history.size > 256 || historyChars > 262_144) historyChars -= history.removeFirst().length
        return model.accept(clean).also { if (it) revision++ }
    }

    fun markGap(): Boolean {
        if (stale) return false
        stale = true
        revision++
        return true
    }

    fun reset(): Boolean {
        history.clear()
        historyChars = 0
        stale = false
        model = Model(model.plugin, model.allowed)
        packageRevision++
        revision++
        return true
    }

    fun snapshot(): ConsolePanelSnapshot = ConsolePanelSnapshot(
        revision, model.plugin.id, model.plugin.title, stale, model.plugin.nodes,
        model.values.toMap(), model.evidence.toMap(), packageRevision,
    )

    fun command(
        actionId: String,
        arguments: Map<String, String> = emptyMap(),
        expectedRevision: Long? = null,
    ): String? {
        if (actionId !in model.allowed || expectedRevision != null && expectedRevision != packageRevision) return null
        return model.plugin.commandSpecs[actionId]?.resolve(arguments)
    }

    fun reload(source: ConsolePluginSource, allowedCommands: Set<String> = emptySet()): ConsolePanelSnapshot {
        val candidate = ConsolePluginCompiler.compile(source)
        require(candidate.id != builtIn.id) { "Reserved built-in plugin ID" }
        return install(candidate, allowedCommands)
    }

    fun restoreBuiltIn(): ConsolePanelSnapshot = install(builtIn, builtIn.commandIds)

    private fun install(plugin: CompiledConsolePlugin, allowed: Set<String>): ConsolePanelSnapshot {
        require(plugin.commandIds.containsAll(allowed)) { "Unknown command grant" }
        val next = Model(plugin, allowed.toSet())
        history.forEach(next::accept)
        model = next
        packageRevision++
        revision++
        return snapshot()
    }

    private class Model(val plugin: CompiledConsolePlugin, val allowed: Set<String>) {
        val values = plugin.initial.toMutableMap()
        val evidence = mutableMapOf<String, String>()
        private val fallback = plugin.rules.filter { it.prefix.isEmpty() }
        private val index = plugin.rules.mapNotNull { it.prefix.firstOrNull() }.distinct().associateWith { first ->
            plugin.rules.filter { it.prefix.isEmpty() || it.prefix[0] == first }
        }

        fun accept(raw: String): Boolean {
            val plain = if ('\u001B' in raw) sgr.replace(raw, "") else raw
            if (plain.any(Char::isISOControl)) return false
            val line = paperEnvelope.find(plain)?.let { plain.substring(it.range.last + 1) } ?: plain
            var changed = false
            val excerpt = plain.take(512)
            for (rule in index[line.firstOrNull()] ?: fallback) {
                val value = rule.match(line) ?: continue
                val next = if (rule.operation == "append") {
                    (values[rule.field].orEmpty().lineSequence().filter(String::isNotEmpty).toList() + value)
                        .takeLast(16).joinToString("\n")
                } else value
                if (values.put(rule.field, next) != next) changed = true
                if (evidence.put(rule.field, excerpt) != excerpt) changed = true
            }
            return changed
        }
    }

    private companion object {
        val builtIn = ConsolePluginCompiler.compile(paperPluginSource)
        val sgr = Regex("""\u001b\[[0-9;]{0,32}m""")
        val paperEnvelope = Regex("""^\[[0-9]{2}:[0-9]{2}:[0-9]{2}\](?:\s+\[[^\]\r\n]{1,96}\]){1,2}:\s*""")
    }
}
