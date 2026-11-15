package com.wludy.rolithax.launcher.shared.console

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

internal actual object SchemaNativeBridge {
    private interface SchemaLibrary : Library {
        fun rolithax_schema_create(): Long
        fun rolithax_schema_destroy(handle: Long)
        fun rolithax_schema_request(handle: Long, request: Pointer): Pointer?
        fun rolithax_schema_free(value: Pointer)
    }

    private val library: SchemaLibrary = Native.load(
        System.getProperty("rolithax.schema.library", "rolithax_schema"), SchemaLibrary::class.java,
    )

    actual fun create(): Long = library.rolithax_schema_create()
    actual fun destroy(handle: Long) = library.rolithax_schema_destroy(handle)
    actual fun request(handle: Long, bytes: ByteArray): ByteArray = Memory(bytes.size.toLong() + 1).use { input ->
        input.write(0, bytes, 0, bytes.size)
        input.setByte(bytes.size.toLong(), 0)
        val response = checkNotNull(library.rolithax_schema_request(handle, input)) { "Rolithax Schema 内存不足" }
        try {
            response.getString(0, "UTF-8").encodeToByteArray()
        } finally {
            library.rolithax_schema_free(response)
        }
    }
}
