package com.wludy.rolithax.launcher.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDisplayTest {
    @Test
    fun commonScaledWorkAreaLeavesRoomForSystemUi() {
        val size = fitDesktopWindow(1280, 680)
        assertTrue(size.width <= 1280.dp)
        assertTrue(size.height <= 680.dp)
        assertEquals(DpSize(1232.dp, 616.dp), size)
    }

    @Test
    fun largeWorkAreaRespectsMaximumSize() {
        assertEquals(DpSize(1280.dp, 840.dp), fitDesktopWindow(2560, 1440))
    }
}
