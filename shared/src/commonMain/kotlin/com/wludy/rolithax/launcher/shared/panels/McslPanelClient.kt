package com.wludy.rolithax.launcher.shared.panels

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private const val MAX_RPC_FRAME = 8L * 1024 * 1024
private const val RPC_TIMEOUT = 12_000L

class McslPanelClient(profile: PanelProfile, http: HttpClient) : PanelClient {
    private val endpoint = mcslEndpoint(profile.baseUrl, profile.allowHttp)
    private val token = profile.token.trim().also { require(it.isNotEmpty()) { "请填写 MCSL Token" } }
    private val client = http.config {
        followRedirects = false
        expectSuccess = false
        install(WebSockets) { maxFrameSize = MAX_RPC_FRAME }
    }

    override val powers = setOf(PanelPower.START, PanelPower.STOP, PanelPower.KILL)

    override suspend fun servers(): List<PanelServer> = withRpc { rpc ->
        val result = rpc.call("mcsl.instance.report.list") as? JsonObject
        val reports = result?.get("reports") as? JsonObject ?: invalidResponse()
        reports.map { (id, value) ->
            val report = value as? JsonObject ?: invalidResponse()
            val config = report["config"] as? JsonObject ?: invalidResponse()
            PanelServer(
                id = id,
                name = config.rpcText("name")?.takeIf { it.isNotBlank() } ?: id,
                state = when (report.rpcText("status")) {
                    "running", "0" -> "运行中"
                    "stopped", "1" -> "已停止"
                    "crashed", "2" -> "已崩溃"
                    "starting", "3" -> "启动中"
                    "stopping", "4" -> "停止中"
                    else -> "未知"
                },
            )
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun power(server: PanelServer, action: PanelPower) {
        val method = when (action) {
            PanelPower.START -> "mcsl.instance.start"
            PanelPower.STOP -> "mcsl.instance.stop"
            PanelPower.KILL -> "mcsl.instance.halt"
            PanelPower.RESTART -> throw UnsupportedOperationException("MCSL Future 未提供重启接口")
        }
        withRpc { it.call(method, buildJsonObject { put("instance_id", server.id) }) }
    }

    override suspend fun command(server: PanelServer, command: String) {
        require(command.isNotBlank() && '\n' !in command && '\r' !in command) { "请输入一行命令" }
        withRpc { rpc ->
            rpc.call("mcsl.instance.command.send", buildJsonObject {
                put("instance_id", server.id)
                put("command", command)
            })
        }
    }

    override suspend fun logs(server: PanelServer, onSnapshot: suspend (String) -> Unit) = withRpc { rpc ->
        val args = buildJsonObject { put("instance_id", server.id) }
        var previous: String? = null
        while (true) {
            val result = rpc.call("mcsl.instance.log.get", args) as? JsonObject
            val lines = result?.get("logs") as? JsonArray ?: invalidResponse()
            val snapshot = lines.takeLast(500).joinToString("\n") { line ->
                (line as? JsonPrimitive)?.contentOrNull ?: invalidResponse()
            }.takeLast(256_000)
            if (snapshot != previous) {
                previous = snapshot
                onSnapshot(snapshot)
            }
            delay(2_000)
        }
    }

    override fun close() {
        client.coroutineContext.cancel()
        client.close()
    }

    private suspend fun <T> withRpc(block: suspend (McslRpcSession) -> T): T {
        val session = try {
            withTimeoutOrNull(RPC_TIMEOUT) {
                client.webSocketSession {
                    url {
                        takeFrom(endpoint)
                        parameters.append("token", token)
                    }
                }
            } ?: throw McslRequestException("连接 MCSL Future 超时")
        } catch (e: CancellationException) {
            throw e
        } catch (e: McslRequestException) {
            throw e
        } catch (e: ResponseException) {
            throw McslRequestException(when (e.response.status.value) {
                401 -> "MCSL Token 无效或已过期"
                403 -> "MCSL Future 拒绝连接"
                else -> "无法连接 MCSL Future（HTTP ${e.response.status.value}）"
            })
        } catch (_: Exception) {
            throw McslRequestException("无法建立 MCSL Future WebSocket，请检查地址、证书和 Token")
        }
        try {
            return block(McslRpcSession(session))
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeoutOrNull(1_000) { runCatching { session.close() } }
                } finally {
                    session.coroutineContext.cancel()
                }
            }
        }
    }
}

internal fun mcslEndpoint(input: String, allowHttp: Boolean): Url {
    val text = input.trim()
    require(text.isNotEmpty() && text.none { it in "?#\\" }) { "MCSL 地址无效" }
    val address = if ("://" in text) text else "wss://$text"
    require('@' !in address.substringAfter("://").substringBefore('/')) { "MCSL 地址不得包含凭据" }
    val url = try {
        URLBuilder().takeFrom(address)
    } catch (_: Exception) {
        throw IllegalArgumentException("MCSL 地址无效")
    }
    require(url.host.isNotBlank() && url.encodedPath.trimEnd('/') in setOf("", "/api/v2")) {
        "请填写 MCSL Future 地址或 /api/v2 端点"
    }
    url.protocol = when (url.protocol) {
        URLProtocol.WSS, URLProtocol.HTTPS -> URLProtocol.WSS
        URLProtocol.WS, URLProtocol.HTTP -> {
            require(allowHttp) { "MCSL 明文连接需要明确允许" }
            URLProtocol.WS
        }
        else -> throw IllegalArgumentException("MCSL 地址仅支持 ws、wss、http 和 https")
    }
    url.encodedPath = "/api/v2"
    return url.build()
}

private class McslRpcSession(private val session: DefaultClientWebSocketSession) {
    private var id = 0L

    suspend fun call(method: String, params: JsonObject = buildJsonObject {}): JsonElement {
        val requestId = (++id).toString()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", method)
            put("params", params)
        }
        return try {
            withTimeoutOrNull(RPC_TIMEOUT) {
                session.send(Frame.Text(request.toString()))
                readResponse(requestId)
            } ?: throw McslRequestException("MCSL Future 请求超时")
        } catch (e: CancellationException) {
            throw e
        } catch (e: McslRequestException) {
            throw e
        } catch (_: Exception) {
            throw McslRequestException("MCSL Future 请求失败或连接已关闭")
        }
    }

    private suspend fun readResponse(id: String): JsonElement {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull()
                ?: throw McslRequestException("MCSL Future 连接已关闭")
            if (frame !is Frame.Text) continue
            val message = Json.parseToJsonElement(frame.readText()) as? JsonObject ?: invalidResponse()
            if (message.rpcText("id") != id) continue
            if (message.rpcText("jsonrpc") != "2.0") invalidResponse()
            if ("error" in message) {
                val code = ((message["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
                    ?: invalidResponse()
                if (code == -32001) throw McslRequestException("MCSL Token 缺少执行此操作的权限")
                throw McslRequestException("MCSL Future 拒绝请求（JSON-RPC $code）")
            }
            return message["result"] ?: invalidResponse()
        }
    }
}

private fun JsonObject.rpcText(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun invalidResponse(): Nothing = throw McslRequestException("MCSL Future 响应格式不正确")

private class McslRequestException(message: String) : IllegalStateException(message)
