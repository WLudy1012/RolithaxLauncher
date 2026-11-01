package com.wludy.rolithax.launcher.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import java.awt.Window

private interface DwmApi : Library {
    fun DwmSetWindowAttribute(window: HWND, attribute: Int, value: IntByReference, size: Int): Int
}

internal fun styleWindowsTitleBar(window: Window, dark: Boolean) {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
    runCatching {
        val api = Native.load("dwmapi", DwmApi::class.java)
        val handle = HWND(Native.getComponentPointer(window))
        fun set(attribute: Int, value: Int) {
            api.DwmSetWindowAttribute(handle, attribute, IntByReference(value), Int.SIZE_BYTES)
        }
        set(20, if (dark) 1 else 0)
        set(33, 2)
        set(35, if (dark) 0x202020 else 0xF3F3F3)
        set(36, if (dark) 0xF4F4F4 else 0x1B1B1B)
    }
}
