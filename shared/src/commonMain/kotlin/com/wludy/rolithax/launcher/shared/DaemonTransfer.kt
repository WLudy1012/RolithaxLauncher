package com.wludy.rolithax.launcher.shared

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement

const val DAEMON_UPLOAD_CHUNK_SIZE = 10 * 1024 * 1024

@Serializable
private data class UploadInitResponse(val uploadId: String = "")
@Serializable
private data class FinishUpload(val totalChunks: Int)
@Serializable
private data class SaveUpload(val uploadId: String, val fileName: String, val currentPath: String)

suspend fun DaemonClient.beginUpload(): String {
    val data = post("api/files/upload/init", "{}") ?: throw DaemonException("Daemon 未返回上传任务")
    val id = json.decodeFromJsonElement<UploadInitResponse>(data).uploadId
    return safeTransferId(id)
}

suspend fun DaemonClient.uploadChunk(uploadId: String, index: Int, bytes: ByteArray) {
    val id = safeTransferId(uploadId)
    require(index >= 0 && bytes.size <= DAEMON_UPLOAD_CHUNK_SIZE)
    client.preparePost("$endpoint/api/files/upload/chunk/$id") {
        timeout { requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS }
        if (apiKey.isNotBlank()) header("x-api-key", apiKey)
        setBody(MultiPartFormDataContent(formData {
            append("index", index.toString())
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentType, "application/octet-stream")
                append(HttpHeaders.ContentDisposition, "filename=\"chunk_$index\"")
            })
        }))
    }.execute { response -> unwrap(response.status.value, response.status.isSuccess(), response.readLimitedText()) }
}

suspend fun DaemonClient.finishUpload(uploadId: String, totalChunks: Int) {
    require(totalChunks > 0)
    post("api/files/upload/finish/${safeTransferId(uploadId)}", json.encodeToString(FinishUpload(totalChunks)))
}

suspend fun DaemonClient.discardUpload(uploadId: String) {
    post("api/files/upload/delete/${safeTransferId(uploadId)}", "{}")
}

suspend fun DaemonClient.saveUpload(instanceId: Long, uploadId: String, fileName: String, currentPath: String) {
    require(instanceId > 0 && validRemoteLeaf(fileName) && validRemotePath(currentPath, allowEmpty = true))
    post("api/files/instance/$instanceId/upload", json.encodeToString(SaveUpload(safeTransferId(uploadId), fileName, currentPath)))
}

suspend fun DaemonClient.downloadFile(instanceId: Long, path: String, consume: suspend (ByteReadChannel, Long?) -> Unit) {
    require(instanceId > 0 && validRemotePath(path))
    client.prepareGet("$endpoint/api/files/instance/$instanceId/download") {
        timeout { requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS }
        if (apiKey.isNotBlank()) header("x-api-key", apiKey)
        parameter("path", path)
    }.execute { response ->
        if (response.status.value != 200) throw DaemonException("下载请求失败：HTTP ${response.status.value}")
        if (!response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/octet-stream", true)) throw DaemonException("下载响应类型无效")
        consume(response.bodyAsChannel(), response.headers[HttpHeaders.ContentLength]?.toLongOrNull())
    }
}

fun validRemoteLeaf(value: String): Boolean = value.isNotBlank() && value !in setOf(".", "..") && value.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }
fun validRemotePath(value: String, allowEmpty: Boolean = false): Boolean =
    if (value.isEmpty()) allowEmpty else value.split('/').all(::validRemoteLeaf)

private fun safeTransferId(value: String): String {
    require(value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "上传标识无效" }
    return value
}
