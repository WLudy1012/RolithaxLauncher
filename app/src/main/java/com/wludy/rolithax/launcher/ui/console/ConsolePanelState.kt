package com.wludy.rolithax.launcher.ui.console

import com.wludy.rolithax.launcher.shared.console.ConsolePanelSnapshot
import com.wludy.rolithax.launcher.shared.console.ConsolePluginCompiler
import com.wludy.rolithax.launcher.shared.console.ConsolePluginException
import com.wludy.rolithax.launcher.shared.console.ConsolePluginSource
import com.wludy.rolithax.launcher.shared.console.ConsolePluginWorkspace
import com.wludy.rolithax.launcher.shared.console.SchemaAction
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConsolePanelUiState(
    val snapshot: ConsolePanelSnapshot,
    val commands: Map<String, String>,
    val runtimeError: String? = null,
    val actions: Map<String, SchemaAction> = emptyMap(),
)

internal class ConsolePanelState {
    private val workspace = ConsolePluginWorkspace()
    private var commands = builtInCommands
    private var actions = emptyMap<String, SchemaAction>()
    private val mutable = MutableStateFlow(ConsolePanelUiState(workspace.snapshot(), commands))
    val state = mutable.asStateFlow()
    private var changed = false
    private var runtimeError: String? = null

    fun onLine(line: String) {
        try {
            changed = workspace.updateLog(line) || changed
        } catch (error: ConsolePluginException) {
            runtimeError = error.message
            workspace.markGap()
            changed = true
        }
    }

    fun markGap() {
        changed = workspace.markGap() || changed
    }

    fun reset() {
        runtimeError = null
        changed = workspace.reset() || changed
        publish()
    }

    fun publish() {
        if (!changed) return
        changed = false
        mutable.value = ConsolePanelUiState(workspace.snapshot(), commands, runtimeError, actions)
    }

    fun load(source: ConsolePluginSource?, allowedCommands: Set<String>) {
        val compiled = source?.let(ConsolePluginCompiler::compile)
        val nextCommands = compiled?.commands?.filterKeys { it in allowedCommands } ?: builtInCommands
        val snapshot = if (source == null) workspace.restoreBuiltIn() else workspace.reload(source, allowedCommands)
        commands = nextCommands
        actions = compiled?.actions?.filterKeys { it in allowedCommands }.orEmpty()
        runtimeError = null
        mutable.value = ConsolePanelUiState(snapshot, commands, actions = actions)
        changed = false
    }

    fun command(actionId: String, arguments: Map<String, String>, packageRevision: Long): String? =
        workspace.command(actionId, arguments, expectedRevision = packageRevision)

    fun close() = workspace.close()

    fun prepare(actionId: String, revision: Long): SchemaAction? = workspace.prepareAction(actionId, revision)

    fun result(kind: String, payload: JsonObject, revision: Long) {
        try {
            changed = workspace.result(kind, payload, revision) || changed
        } catch (error: ConsolePluginException) {
            runtimeError = error.message
            changed = true
        }
        publish()
    }

    private companion object {
        val builtInCommands = ConsolePluginCompiler.compile(ConsolePluginCompiler.paperSource).commands
    }
}
