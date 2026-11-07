@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.wludy.rolithax.launcher.shared

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.posix.memcpy
import kotlin.native.CName

@CName("rolithax_abi_version")
fun abiVersion(): Int = 1

@CName("rolithax_session_open")
fun sessionOpen(configJson: CPointer<ByteVar>?, errorJson: CPointer<CPointerVar<ByteVar>>?): COpaquePointer? {
    errorJson?.pointed?.value = null
    return try {
        val config = parseObject(configJson)
        StableRef.create(WindowsDaemonSession(
            config.requiredString("baseUrl"),
            config.string("apiKey").orEmpty(),
            config.flag("allowHttp"),
            config.flag("allowSelfSigned"),
        )).asCPointer()
    } catch (error: Exception) {
        errorJson?.pointed?.value = failure(error).toNativeString()
        null
    }
}

@CName("rolithax_session_call")
fun sessionCall(
    session: COpaquePointer?,
    operation: CPointer<ByteVar>?,
    argsJson: CPointer<ByteVar>?,
): CPointer<ByteVar> {
    val daemon = session?.asStableRef<WindowsDaemonSession>()?.get()
    return try {
        requireNotNull(daemon) { "连接已关闭" }
        val name = requireNotNull(operation?.toKString()) { "缺少操作名称" }
        val args = parseObject(argsJson)
        val data = runBlocking {
            when (name) {
                "downloadFile", "uploadFile" -> daemon.call(name, args)
                "resourceInstall" -> withTimeout(90_000) { daemon.call(name, args) }
                else -> withTimeout(30_000) { daemon.call(name, args) }
            }
        }
        buildJsonObject {
            put("ok", true)
            put("data", data ?: JsonNull)
        }.toNativeString()
    } catch (error: Exception) {
        failure(error, daemon).toNativeString()
    }
}

@CName("rolithax_session_close")
fun sessionClose(session: COpaquePointer?) {
    val ref = session?.asStableRef<WindowsDaemonSession>() ?: return
    try {
        runBlocking { ref.get().close() }
    } finally {
        ref.dispose()
    }
}

@CName("rolithax_string_free")
fun stringFree(value: CPointer<ByteVar>?) {
    if (value != null) nativeHeap.free(value.rawValue)
}

private fun parseObject(value: CPointer<ByteVar>?): JsonObject =
    value?.toKString()?.let {
        Json.parseToJsonElement(it) as? JsonObject ?: throw SerializationException("Expected a JSON object")
    } ?: JsonObject(emptyMap())

private fun failure(error: Exception, daemon: WindowsDaemonSession? = null): JsonObject = buildJsonObject {
    put("ok", false)
    val message = when (error) {
        is SerializationException -> "请求或响应格式无效"
        is TimeoutCancellationException -> "请求超时"
        is DaemonException, is IllegalArgumentException -> error.message ?: "请求无效"
        else -> "请求失败，请检查连接或本地文件"
    }
    put("error", daemon?.redact(message) ?: message)
    if (error is DaemonException) {
        error.httpStatus?.let { put("httpStatus", it) }
        error.businessCode?.let { put("businessCode", it) }
    }
}

private fun JsonElement.toNativeString(): CPointer<ByteVar> {
    val bytes = toString().encodeToByteArray()
    val result = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
    bytes.usePinned { memcpy(result, it.addressOf(0), bytes.size.toULong()) }
    result[bytes.size] = 0
    return result
}
