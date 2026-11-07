package com.wludy.rolithax.launcher.shared.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsolePluginTest {
    @Test
    fun paperExtractsEvidenceWithoutTrustingChatOrProducingCommands() {
        val workspace = ConsolePluginWorkspace()
        assertFalse(workspace.updateLog("[12:00:01] [Server thread/INFO]: <Bob> Alex joined the game"))
        assertTrue(workspace.updateLog("[12:00:02] [Server thread/INFO]: Alex joined the game"))
        assertTrue(workspace.updateLog("[12:00:03] [Server thread/INFO]: There are 1 of a max of 20 players online: Alex"))
        assertFalse(workspace.updateLog("[12:00:04] [Server thread/INFO]: There are 2 of a max of 20 players online: Alex"))
        val panel = workspace.snapshot()
        assertEquals("Alex", panel.values["online-players"])
        assertEquals("Alex joined the game", panel.values["activity"])
        assertEquals("1 人在线", panel.values["player-summary"])
        assertTrue(panel.evidence.getValue("online-players").contains("12:00:03"))
        assertEquals("list", workspace.command("refresh-players", expectedRevision = panel.packageRevision))
        assertNull(workspace.command("refresh-players", mapOf("value" to "stop")))
    }

    @Test
    fun reloadIsAtomicRevokesOldActionsAndReplaysOnlyData() {
        val workspace = ConsolePluginWorkspace()
        workspace.updateLog("Alex joined the game")
        workspace.markGap()
        val old = workspace.snapshot()
        val source = ConsolePluginCompiler.paperSource.asCommunity()
        assertFailsWith<ConsolePluginException> { workspace.reload(source.copy(ui = "<!DOCTYPE panel>")) }
        assertEquals(old, workspace.snapshot())
        val first = workspace.reload(source)
        assertEquals("Alex joined the game", first.values["activity"])
        assertTrue(first.stale)
        assertNull(workspace.command("refresh-players"))
        val second = workspace.reload(source, setOf("refresh-players"))
        assertTrue(second.packageRevision > first.packageRevision)
        assertNull(workspace.command("refresh-players", expectedRevision = first.packageRevision))
        assertEquals("list", workspace.command("refresh-players", expectedRevision = second.packageRevision))
        assertTrue(workspace.reset())
        assertEquals("", workspace.snapshot().values["activity"])
        assertNull(workspace.command("refresh-players", expectedRevision = second.packageRevision))
    }

    @Test
    fun invalidPackagesAndArgumentsAreRejected() {
        val source = ConsolePluginCompiler.paperSource.asCommunity()
        val bad = listOf(
            source.copy(manifest = source.manifest.replace("\"sdkVersion\": 1", "\"sdkVersion\": 1, \"sdkVersion\": 1")),
            source.copy(ui = source.ui.replace("Paper 服务器", "&external;")),
            source.copy(ui = source.ui.replace("id=\"refresh\"", "id=\"activity-list\"")),
            source.copy(rules = source.rules.replace("text: \"list\"", "text: &anchor \"list\"")),
            source.copy(rules = source.rules.replace("text: \"list\"", "text: \"list\\nstop\"")),
        )
        bad.forEach { assertFailsWith<ConsolePluginException> { ConsolePluginCompiler.compile(it) } }
        val withInput = source.copy(
            ui = source.ui.replace("<button id=\"refresh\"", "<field id=\"player-input\" label=\"Player\" field=\"player\"/><button id=\"refresh\""),
            rules = source.rules.replace("text: \"list\"", "text: \"kick {player}\"\n    player: \"player\""),
        )
        val workspace = ConsolePluginWorkspace()
        workspace.reload(withInput, setOf("refresh-players"))
        assertEquals("kick Alex", workspace.command("refresh-players", mapOf("player" to "Alex")))
        assertNull(workspace.command("refresh-players", mapOf("player" to "Alex\nstop")))
        assertNull(workspace.command("refresh-players", mapOf("player" to "Alex all")))
    }

    private fun ConsolePluginSource.asCommunity() =
        copy(manifest = manifest.replace("rolithax.paper", "example.paper-panel"))
}
