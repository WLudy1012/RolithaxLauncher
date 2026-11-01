package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.serialization.encodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

class DaemonException(
    message: String,
    val httpStatus: Int? = null,
    val businessCode: Int? = null,
    val serverMessage: String? = null,
) : Exception(message)

data class DaemonResponse(val message: String?, val data: JsonElement?)

class DaemonClient(
    baseUrl: String,
    internal val apiKey: String,
    httpClient: HttpClient,
    allowHttp: Boolean = false,
) {
    internal val endpoint = normalizeDaemonUrl(baseUrl, allowHttp)
    internal val client = httpClient.config {
        install(HttpTimeout)
        followRedirects = false
        expectSuccess = false
    }
    @OptIn(ExperimentalSerializationApi::class)
    internal val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun close() = client.close()

    suspend fun statusPayload(): JsonElement? = get("api/status")

    suspend fun javaListPayload(refresh: Boolean = false): JsonElement? =
        get("api/java/list", mapOf("refresh" to refresh.toString()))

    suspend fun listInstancesPayload(): JsonElement? = get("api/instance/list")

    suspend fun listInstances(): List<DaemonInstance> {
        val data = listInstancesPayload() ?: throw DaemonException("Daemon 未返回实例列表")
        return json.decodeFromJsonElement(data)
    }

    suspend fun instanceInfoPayload(id: Long): JsonElement =
        get("api/instance/info", mapOf("id" to id.toString()))
            ?: throw DaemonException("返回数据为空")

    suspend fun instanceInfo(id: Long): DaemonInstanceInfo =
        json.decodeFromJsonElement(instanceInfoPayload(id))

    suspend fun action(id: Long, action: String) {
        require(action in setOf("start", "stop", "restart", "forceExit", "backup", "agreeEula?true")) { "不支持的实例操作" }
        actionWithMessage(id, action)
    }

    suspend fun actionWithMessage(id: Long, action: String): String? =
        postMessage("api/instance/action", json.encodeToString(ActionRequest(id, action)))

    suspend fun listFilesPayload(id: Long, path: String = ""): JsonElement? =
        get("api/files/instance/$id/lists", mapOf("path" to path))

    suspend fun listFiles(id: Long, path: String = ""): List<DaemonFile> {
        val data = listFilesPayload(id, path)
            ?: throw DaemonException("Daemon 未返回文件列表")
        return json.decodeFromJsonElement(data)
    }

    suspend fun fileContentPayload(id: Long, path: String): JsonElement? =
        get("api/files/instance/$id/content", mapOf("path" to path))

    suspend fun fileContent(id: Long, path: String): String =
        json.decodeFromJsonElement(fileContentPayload(id, path) ?: throw DaemonException("Daemon 未返回文件内容"))

    suspend fun saveFileContent(id: Long, path: String, content: String) {
        saveFileContentWithMessage(id, path, content)
    }

    suspend fun saveFileContentWithMessage(id: Long, path: String, content: String): String? =
        postMessage("api/files/instance/$id/content", json.encodeToString(SaveFileRequest(path, content)))

    private suspend fun postMessage(path: String, body: String): String? =
        postResponse(path, body).message

    internal suspend fun postResponse(path: String, body: String, allowAny2xx: Boolean = false): DaemonResponse =
        client.preparePost("$endpoint/$path") {
            if (apiKey.isNotBlank()) header("x-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            val responseBody = response.readLimitedText()
            val data = unwrap(response.status.value, response.status.isSuccess(), responseBody, allowAny2xx = allowAny2xx)
            val message = json.decodeFromString<ApiResponse<JsonElement>>(responseBody).message
            DaemonResponse(if (apiKey.isEmpty()) message else message?.replace(apiKey, "***"), data)
        }

    internal suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement? {
        return client.prepareGet("$endpoint/$path") {
            if (apiKey.isNotBlank()) header("x-api-key", apiKey)
            query.forEach { (key, value) -> parameter(key, value) }
        }.execute { response ->
            unwrap(response.status.value, response.status.isSuccess(), response.readLimitedText())
        }
    }

    internal suspend fun getEmptyOnNotFound(path: String, query: Map<String, String>): JsonElement? =
        client.prepareGet("$endpoint/$path") {
            if (apiKey.isNotBlank()) header("x-api-key", apiKey)
            query.forEach { (key, value) -> parameter(key, value) }
        }.execute { response ->
            if (response.status.value == 404) return@execute null
            if (!response.status.isSuccess()) throw DaemonException("获取列表失败", response.status.value)
            unwrap(response.status.value, true, response.readLimitedText())
        }

    internal suspend fun post(path: String, body: String, allowAccepted: Boolean = false): JsonElement? {
        return client.preparePost("$endpoint/$path") {
            if (apiKey.isNotBlank()) header("x-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            unwrap(response.status.value, response.status.isSuccess(), response.readLimitedText(), allowAccepted)
        }
    }

    internal fun unwrap(status: Int, success: Boolean, body: String, allowAccepted: Boolean = false, allowAny2xx: Boolean = false): JsonElement? {
        val envelope = runCatching { json.decodeFromString<ApiResponse<JsonElement>>(body) }.getOrNull()
        if (!success || envelope == null || (envelope.code != 200 && !(allowAccepted && envelope.code == 202) && !(allowAny2xx && envelope.code in 200..299))) {
            val fallback = if (!success) "HTTP $status 请求失败" else "Daemon 响应无效"
            val message = envelope?.message?.takeIf { it.isNotBlank() } ?: fallback
            fun redact(value: String): String = if (apiKey.isEmpty()) value else value.replace(apiKey, "***")
            throw DaemonException(
                message = redact(message),
                httpStatus = status.takeUnless { success },
                businessCode = envelope?.code,
                serverMessage = envelope?.message?.let(::redact),
            )
        }
        return envelope.data?.takeUnless { it is JsonNull }
    }
}

internal suspend fun HttpResponse.readLimitedText(limit: Int = 4 * 1024 * 1024): String {
    val bytes = bodyAsChannel().readRemaining(limit.toLong() + 1).readBytes()
    if (bytes.size > limit) throw DaemonException("Daemon 响应过大")
    return bytes.decodeToString()
}

fun normalizeDaemonUrl(input: String, allowHttp: Boolean = false): String {
    val value = input.trim().trimEnd('/')
    require(value.isNotEmpty()) { "Daemon 地址不能为空" }
    val url = if (value.contains("://")) value else "https://$value"
    require(url.startsWith("https://", true) || (allowHttp && url.startsWith("http://", true))) {
        "只允许 HTTPS；使用 HTTP 需要明确允许"
    }
    val authority = url.substringAfter("://").substringBefore('/')
    require(authority.isNotBlank() && '@' !in authority && '?' !in url && '#' !in url) { "Daemon 地址无效" }
    return url
}
