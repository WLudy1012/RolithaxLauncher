package com.wludy.rolithax.launcher.desktop

import com.wludy.rolithax.launcher.shared.DaemonInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionStateTest {
    @Test
    fun removingInactiveConnectionPreservesCurrentContext() {
        val current = DaemonInstance(7, status = 2)
        val state = DesktopState(
            selectedId = 1, connections = listOf(ConnectionItem(1, "one"), ConnectionItem(2, "two")),
            instances = listOf(current), instancesLoaded = true, consoleInstance = current,
            consoleConnected = true, monitorConnected = true, creationId = "task", fileContent = "draft",
        )
        assertEquals(state.copy(connections = listOf(ConnectionItem(1, "one"))), state.withoutInactiveConnection(2))
    }

    @Test
    fun countsDistinguishUnavailableFromAnEmptySuccessfulList() {
        val initial = DesktopState(selectedId = 1)
        assertEquals("未知", overviewInstanceCount(initial, connected = true, runningOnly = false))
        assertEquals("加载中", overviewInstanceCount(initial.copy(busy = true), connected = true, runningOnly = false))
        val loaded = initial.withInstances(emptyList())
        assertTrue(loaded.instancesLoaded)
        assertEquals("0", overviewInstanceCount(loaded, connected = true, runningOnly = false))
        assertEquals("—", overviewInstanceCount(loaded, connected = false, runningOnly = false))
        assertFalse(initial.instancesLoaded)
    }

    @Test
    fun displayAddressOmitsCredentialsAndQuery() {
        assertEquals("https://example.com:8443", displayDaemonAddress("https://user:password@example.com:8443/path?token=secret"))
    }
}
