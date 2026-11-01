package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class MslCoreClient(httpClient: HttpClient) {
    private val client = httpClient.config { followRedirects = false; expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private var bedrockCores: Set<String> = emptySet()

    fun close() = client.close()

    suspend fun cores(): List<String> {
        val groups = rootObject(get("mirrors"))
        bedrockCores = (groups["bedrockCore"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        return groups.values.flatMap { value ->
            (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        }.filter { it.isNotBlank() }.distinct()
    }

    fun isBedrock(core: String): Boolean = core in bedrockCores

    suspend fun versions(core: String): List<String> {
        require(validId(core))
        return (rootObject(get("mirrors/$core"))["versions"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }

    suspend fun builds(core: String, version: String): List<String> {
        require(validId(core) && validId(version))
        return (get("mirrors/$core/$version") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }

    suspend fun download(core: String, version: String, build: String): MslCoreDownload {
        require(validId(core) && validId(version) && validId(build))
        val data = rootObject(get("download/server/$core/$version", mapOf("build" to build)))
        val url = data["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sha = data["sha256"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(url.startsWith("https://", true) && sha.length == 64 && sha.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) { "核心下载缺少 HTTPS 地址或完整 SHA-256" }
        val mirrors = (data["mirrors"] as? JsonArray)?.mapNotNull { entry ->
            val mirror = entry as? JsonObject ?: return@mapNotNull null
            val mirrorUrl = mirror["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val mirrorSha = mirror["sha256"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: sha
            if (!mirrorUrl.startsWith("https://", true) || mirrorSha.length != 64 || !mirrorSha.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return@mapNotNull null
            DaemonCoreMirror(mirrorUrl, mirrorSha)
        }.orEmpty()
        return MslCoreDownload(url, sha, mirrors)
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): kotlinx.serialization.json.JsonElement {
        MslApiRateLimiter.awaitSlot()
        return client.prepareGet("https://api.mslmc.cn/v4/$path") {
            header("User-Agent", "Rolithax Launcher (https://github.com/WLudy1012/RolithaxLauncher)")
            query.forEach { (key, value) -> parameter(key, value) }
        }.execute { response ->
            if (!response.status.isSuccess()) throw DaemonException("MSLAPI 请求失败")
            val bytes = response.bodyAsChannel().readRemaining(4 * 1024 * 1024L + 1).readBytes()
            if (bytes.size > 4 * 1024 * 1024) throw DaemonException("MSLAPI 响应过大")
            val root = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: throw DaemonException("MSLAPI 响应无效")
            if (root["code"]?.jsonPrimitive?.intOrNull != 200) throw DaemonException("MSLAPI 请求失败")
            root["data"] ?: throw DaemonException("MSLAPI 响应缺少数据")
        }
    }

    private fun rootObject(element: kotlinx.serialization.json.JsonElement): JsonObject = when (element) {
        is JsonObject -> element
        is JsonArray -> element.firstOrNull() as? JsonObject ?: throw DaemonException("MSLAPI 响应无效")
        else -> throw DaemonException("MSLAPI 响应无效")
    }

    private fun validId(value: String): Boolean = value.isNotBlank() && value.all { it.isLetterOrDigit() || it in ".-_+" }
}

data class MslCoreDownload(val url: String, val sha256: String, val mirrors: List<DaemonCoreMirror> = emptyList())

internal class MslRequestLimiter(
    private val nowMillis: () -> Long,
    private val waitMillis: suspend (Long) -> Unit,
) {
    private val lock = Mutex()
    private var lastRequest: Long? = null

    suspend fun awaitSlot() {
        lock.withLock {
            val elapsed = lastRequest?.let { (nowMillis() - it).coerceAtLeast(0) } ?: 150
            if (elapsed < 150) waitMillis(150 - elapsed)
            lastRequest = nowMillis()
        }
    }
}

object MslApiRateLimiter {
    private val origin = TimeSource.Monotonic.markNow()
    private val limiter = MslRequestLimiter({ origin.elapsedNow().inWholeMilliseconds }, { delay(it) })
    suspend fun awaitSlot() = limiter.awaitSlot()
}
