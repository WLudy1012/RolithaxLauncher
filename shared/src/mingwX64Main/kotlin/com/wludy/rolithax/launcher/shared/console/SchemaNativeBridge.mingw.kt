@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.wludy.rolithax.launcher.shared.console

import kotlinx.cinterop.*
import platform.windows.GetModuleFileNameW
import platform.windows.GetModuleHandleW
import platform.windows.GetProcAddress
import platform.windows.LoadLibraryExW

internal actual object SchemaNativeBridge {
    private val module = memScoped {
        val path = allocArray<UShortVar>(32768)
        val core = checkNotNull(GetModuleHandleW("rolithax_core.dll")) { "无法定位 Rolithax 核心模块" }
        val length = GetModuleFileNameW(core, path, 32768u).toInt()
        check(length in 1..32767) { "无法定位 Rolithax Schema" }
        val executable = path.toKStringFromUtf16()
        val library = executable.substringBeforeLast('\\') + "\\rolithax_schema.dll"
        checkNotNull(LoadLibraryExW(library, null, 0x00000100u or 0x00001000u)) {
            "无法加载 rolithax_schema.dll，请重新安装 Rolithax"
        }
    }
    private val createFunction = checkNotNull(GetProcAddress(module, "rolithax_schema_create"))
        .reinterpret<CFunction<() -> ULong>>()
    private val destroyFunction = checkNotNull(GetProcAddress(module, "rolithax_schema_destroy"))
        .reinterpret<CFunction<(ULong) -> Unit>>()
    private val requestFunction = checkNotNull(GetProcAddress(module, "rolithax_schema_request"))
        .reinterpret<CFunction<(ULong, CPointer<ByteVar>?) -> CPointer<ByteVar>?>>()
    private val freeFunction = checkNotNull(GetProcAddress(module, "rolithax_schema_free"))
        .reinterpret<CFunction<(CPointer<ByteVar>?) -> Unit>>()

    actual fun create(): Long = createFunction().toLong()
    actual fun destroy(handle: Long) = destroyFunction(handle.toULong())
    actual fun request(handle: Long, bytes: ByteArray): ByteArray {
        val input = bytes + byteArrayOf(0)
        val output = input.usePinned { requestFunction(handle.toULong(), it.addressOf(0)) }
        checkNotNull(output) { "Rolithax Schema 内存不足" }
        return try { output.toKString().encodeToByteArray() } finally { freeFunction(output) }
    }
}
