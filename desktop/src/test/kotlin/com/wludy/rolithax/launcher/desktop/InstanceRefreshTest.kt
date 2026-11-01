package com.wludy.rolithax.launcher.desktop

import com.wludy.rolithax.launcher.shared.DaemonInstance
import com.wludy.rolithax.launcher.shared.DaemonInstanceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstanceRefreshTest {
    @Test
    fun refreshesActiveInstanceAndInfoWithoutLosingOtherPageState() {
        val old = DaemonInstance(id = 42, status = 0)
        val new = old.copy(status = 2, statusText = "运行中")
        val state = DesktopState(detailInstance = old, fileInstance = old, instanceInfo = DaemonInstanceInfo(id = 42, status = 0, uptime = "1h"), fileContent = "editing")
        val updated = state.withInstances(listOf(new))
        assertEquals(new, updated.detailInstance)
        assertEquals(new, updated.fileInstance)
        assertEquals(2, updated.instanceInfo?.status)
        assertEquals("1h", updated.instanceInfo?.uptime)
        assertEquals("editing", updated.fileContent)
        assertNull(updated.consoleInstance)
    }
}
