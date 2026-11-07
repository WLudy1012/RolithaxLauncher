package com.wludy.rolithax.launcher.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

internal fun fitDesktopWindow(width: Int, height: Int): DpSize = DpSize(
    (width - 48).coerceIn(360, 1280).dp,
    (height - 64).coerceIn(300, 840).dp,
)

internal fun initialDesktopSize(): DpSize = runCatching {
    val display = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(display)
    fitDesktopWindow(display.bounds.width - insets.left - insets.right, display.bounds.height - insets.top - insets.bottom)
}.getOrDefault(DpSize(1120.dp, 720.dp))
