@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.wludy.rolithax.launcher.shared

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.windows.CREATE_NEW
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.DeleteFileW
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.HANDLE
import platform.windows.MOVEFILE_REPLACE_EXISTING
import platform.windows.MoveFileExW
import platform.windows.OPEN_EXISTING
import platform.windows.ReadFile
import platform.windows.WriteFile
import kotlin.random.Random

internal suspend fun DaemonClient.downloadToWindows(instanceId: Long, path: String, localPath: String) {
    require('\u0000' !in localPath) { "本地文件路径无效" }
    val stagingPath = "$localPath.rolithax-${Random.nextLong().toULong().toString(16)}.part"
    val handle = openWindowsFile(stagingPath, GENERIC_WRITE.toUInt(), 0u, CREATE_NEW.toUInt())
    try {
        try {
            downloadFile(instanceId, path) { source, expectedLength ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = source.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (expectedLength != null && total > expectedLength) throw DaemonException("文件长度与服务器声明不符")
                    writeWindowsFile(handle, buffer, count)
                }
                if (expectedLength != null && total != expectedLength) throw DaemonException("文件下载不完整")
            }
        } finally {
            CloseHandle(handle)
        }
        currentCoroutineContext().ensureActive()
        val moved = MoveFileExW(stagingPath, localPath, MOVEFILE_REPLACE_EXISTING.toUInt())
        if (moved == 0) throw DaemonException("无法保存下载文件，请检查目标文件或目录权限")
    } finally {
        DeleteFileW(stagingPath)
    }
}

internal suspend fun DaemonClient.uploadFromWindows(instanceId: Long, path: String, localPath: String, name: String) {
    require('\u0000' !in localPath && validRemotePath(path, allowEmpty = true) && validRemoteLeaf(name)) { "文件路径无效" }
    val handle = openWindowsFile(localPath, GENERIC_READ, FILE_SHARE_READ.toUInt(), OPEN_EXISTING.toUInt())
    try {
        val uploadId = beginUpload()
        var saved = false
        try {
            val buffer = ByteArray(DAEMON_UPLOAD_CHUNK_SIZE)
            var index = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = readWindowsFile(handle, buffer)
                if (count == 0 && index > 0) break
                uploadChunk(uploadId, index, if (count == buffer.size) buffer else buffer.copyOf(count))
                index++
                if (count < buffer.size) break
            }
            finishUpload(uploadId, index)
            saveUpload(instanceId, uploadId, name, path)
            saved = true
        } finally {
            if (!saved) withContext(NonCancellable) { runCatching { discardUpload(uploadId) } }
        }
    } finally {
        CloseHandle(handle)
    }
}

private fun openWindowsFile(path: String, access: UInt, share: UInt, disposition: UInt): HANDLE {
    val handle = CreateFileW(path, access, share, null, disposition, FILE_ATTRIBUTE_NORMAL.toUInt(), null)
    if (handle == null || handle == (-1L).toCPointer<CPointed>()) throw DaemonException("无法打开本地文件，请检查路径和权限")
    return handle
}

private fun readWindowsFile(handle: HANDLE, buffer: ByteArray): Int = memScoped {
    val read = alloc<DWORDVar>()
    val success = buffer.usePinned { ReadFile(handle, it.addressOf(0), buffer.size.toUInt(), read.ptr, null) }
    if (success == 0) throw DaemonException("无法读取本地文件")
    read.value.toInt()
}

private fun writeWindowsFile(handle: HANDLE, buffer: ByteArray, count: Int) = memScoped {
    val written = alloc<DWORDVar>()
    val success = buffer.usePinned { WriteFile(handle, it.addressOf(0), count.toUInt(), written.ptr, null) }
    if (success == 0 || written.value.toInt() != count) throw DaemonException("无法写入本地文件")
}
