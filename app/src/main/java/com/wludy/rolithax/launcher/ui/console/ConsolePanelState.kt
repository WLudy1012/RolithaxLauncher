package com.wludy.rolithax.launcher.ui.console

import com.wludy.rolithax.launcher.shared.console.ConsolePanelSnapshot
import com.wludy.rolithax.launcher.shared.console.ConsolePluginCompiler
import com.wludy.rolithax.launcher.shared.console.ConsolePluginSource
import com.wludy.rolithax.launcher.shared.console.ConsolePluginWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConsolePanelUiState(
    val snapshot: ConsolePanelSnapshot,
    val commands: Map<String, String>,
)

internal class ConsolePanelState {
    private val workspace = ConsolePluginWorkspace()
    private var commands = builtInCommands
    private val mutable = MutableStateFlow(ConsolePanelUiState(workspace.snapshot(), commands))
    val state = mutable.asStateFlow()
    private var changed = false

    fun onLine(line: String) {
        changed = workspace.updateLog(line) || changed
    }

    fun markGap() {
        changed = workspace.markGap() || changed
    }

    fun reset() {
        changed = workspace.reset() || changed
        publish()
    }

    fun publish() {
        if (!changed) return
        changed = false
        mutable.value = ConsolePanelUiState(workspace.snapshot(), commands)
    }

    fun load(source: ConsolePluginSource?, allowedCommands: Set<String>) {
        val nextCommands = if (source == null) builtInCommands else
            ConsolePluginCompiler.compile(source).commands.filterKeys { it in allowedCommands }
        val snapshot = if (source == null) workspace.restoreBuiltIn() else workspace.reload(source, allowedCommands)
        commands = nextCommands
        mutable.value = ConsolePanelUiState(snapshot, commands)
        changed = false
    }

    fun command(actionId: String, arguments: Map<String, String>, packageRevision: Long): String? =
        workspace.command(actionId, arguments, expectedRevision = packageRevision)

    private companion object {
        val builtInCommands = ConsolePluginCompiler.compile(ConsolePluginCompiler.paperSource).commands
    }
}
