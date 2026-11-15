package com.wludy.rolithax.launcher.shared.panels

import com.wludy.rolithax.launcher.shared.normalizeDaemonUrl
import com.wludy.rolithax.launcher.shared.readLimitedText
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal class PanelHttp(private val profile: PanelProfile, http: HttpClient) {
    val base = normalizeDaemonUrl(profile.baseUrl, profile.allowHttp)
    private val client = http.config {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    suspend fun request(path: String, query: Map<String, String> = emptyMap(), body: JsonObject? = null): JsonElement = try {
        client.prepareRequest("$base/$path") {
            method = if (body == null) HttpMethod.Get else HttpMethod.Post
            header(HttpHeaders.UserAgent, "Rolithax-Launcher")
            header(HttpHeaders.Accept, "application/json")
            when (profile.kind) {
                PanelKind.MCSM -> {
                    parameter("apikey", profile.token)
                    header("X-Requested-With", "XMLHttpRequest")
                    contentType(ContentType.Application.Json.withCharset(Charsets.UTF_8))
                }
                PanelKind.PTERODACTYL -> header(HttpHeaders.Authorization, "Bearer ${profile.token}")
                PanelKind.MCSL -> error("MCSL 使用 WebSocket RPC")
            }
            query.forEach { (name, value) -> parameter(name, value) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }.execute {
            if (!it.status.isSuccess()) fail("请求失败（HTTP ${it.status.value}）")
            if (profile.kind == PanelKind.PTERODACTYL && body != null) {
                if (it.status != HttpStatusCode.NoContent) fail("操作未返回预期的确认响应")
                return@execute JsonNull
            }
            val text = it.readLimitedText()
            val payload = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: fail("返回无效 JSON")
            if (profile.kind == PanelKind.MCSM) {
                if (payload.number("status") != 200) fail("拒绝请求，请检查 API Key 和实例权限")
                payload["data"] ?: fail("未返回请求数据")
            } else payload
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: PanelRequestException) {
        throw error
    } catch (_: Exception) {
        fail("网络请求失败，请检查地址和网络")
    }

    private fun fail(message: String): Nothing = throw PanelRequestException("${profile.kind.title} $message")
    fun close() = client.close()
}

class PanelRequestException internal constructor(message: String) : IllegalStateException(message)

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
internal fun JsonObject.items(key: String): JsonArray = get(key) as? JsonArray ?: error("面板返回的数据缺少 $key")
