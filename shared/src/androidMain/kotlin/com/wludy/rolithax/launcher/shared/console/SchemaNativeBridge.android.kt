package com.wludy.rolithax.launcher.shared.console

internal actual object SchemaNativeBridge {
    init { System.loadLibrary("rolithax_schema") }

    actual fun create(): Long = nativeCreate()
    actual fun destroy(handle: Long) = nativeDestroy(handle)
    actual fun request(handle: Long, bytes: ByteArray): ByteArray = nativeRequest(handle, bytes)

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeRequest(handle: Long, bytes: ByteArray): ByteArray
}
