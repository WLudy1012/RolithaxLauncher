package com.wludy.rolithax.launcher.shared.panels

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class PterodactylPanelClient(private val profile: PanelProfile, http: HttpClient) : PanelClient {
    private val api = PanelHttp(profile, http)
    private val sockets = http.config {
        followRedirects = false
        install(WebSockets) { maxFrameSize = 1024L * 1024 }
    }
    override val powers = PanelPower.entries.toSet()

    override suspend fun servers(): List<PanelServer> {
        val servers = mutableListOf<PanelServer>()
        var page = 1
        do {
            val root = api.request("api/client", mapOf("page" to page.toString())).jsonObject
            check(root.text("object") == "list") { "Pterodactyl 实例列表响应无效" }
            val pagination = root["meta"]?.jsonObject?.get("pagination")?.jsonObject
                ?: error("Pterodactyl 未返回分页信息")
            val pages = pagination.number("total_pages")?.takeIf { it >= 0 } ?: error("Pterodactyl 未返回总页数")
            val entries = root.items("data")
            if (entries.isEmpty()) break
            check(pagination.number("current_page") == page) { "Pterodactyl 返回了错误的页码" }
            for (entry in entries) {
                val data = entry.jsonObject["attributes"]?.jsonObject ?: error("Pterodactyl 实例响应无效")
                val id = data.text("identifier")
                require(id.matches(Regex("[A-Za-z0-9-]+"))) { "Pterodactyl 实例标识无效" }
                val state = try {
                    val usage = api.request("api/client/servers/$id/resources").jsonObject["attributes"]?.jsonObject
                    when (usage?.text("current_state")) {
                        "running" -> "运行中"
                        "starting" -> "启动中"
                        "stopping" -> "停止中"
                        "offline" -> "已停止"
                        else -> "状态未知"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    "状态读取失败"
                }
                servers += PanelServer(id, data.text("name").ifBlank { id }, state,
                    description = data.text("description"))
            }
            page++
        } while (page <= pages)
        return servers
    }

    private fun path(server: PanelServer): String {
        require(server.id.matches(Regex("[A-Za-z0-9-]+"))) { "实例标识无效" }
        return "api/client/servers/${server.id}"
    }

    override suspend fun power(server: PanelServer, action: PanelPower) {
        api.request("${path(server)}/power", body = buildJsonObject { put("signal", action.name.lowercase()) })
    }

    override suspend fun command(server: PanelServer, command: String) {
        require(command.isNotBlank()) { "命令不能为空" }
        api.request("${path(server)}/command", body = buildJsonObject { put("command", command) })
    }

    override suspend fun logs(server: PanelServer, onSnapshot: suspend (String) -> Unit) {
        val credentials = consoleCredentials(server)
        val buffer = StringBuilder()
        sockets.webSocket(urlString = credentials.address.toString(), request = {
            header(HttpHeaders.Origin, pterodactylOrigin(api.base))
        }) {
            var historyRequested = false
            suspend fun authenticate(token: String) {
                send(Frame.Text(buildJsonObject {
                    put("event", "auth")
                    putJsonArray("args") { add(token) }
                }.toString()))
            }
            authenticate(credentials.token)
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val event = runCatching { Json.parseToJsonElement(frame.readText()) as? JsonObject }.getOrNull()
                    ?: error("Wings 返回无效的控制台消息")
                when (event.text("event")) {
                    "auth success" -> if (!historyRequested) {
                        historyRequested = true
                        send(Frame.Text("""{"event":"send logs","args":[]}"""))
                    }
                    "console output" -> {
                        val lines = event["args"] as? JsonArray ?: continue
                        lines.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.forEach { buffer.append(it).append('\n') }
                        if (buffer.length > 65_536) buffer.deleteRange(0, buffer.length - 65_536)
                        onSnapshot(buffer.toString())
                    }
                    "token expiring", "token expired" -> {
                        val renewed = consoleCredentials(server)
                        check(renewed.address == credentials.address) { "控制台地址已变更，请重新连接" }
                        authenticate(renewed.token)
                    }
                    "jwt error", "daemon error" -> error("控制台鉴权或操作失败，请检查实例权限后重新连接")
                }
            }
            error("控制台连接已断开，请重试")
        }
    }

    private suspend fun consoleCredentials(server: PanelServer): ConsoleCredentials {
        val data = api.request("${path(server)}/websocket").jsonObject["data"] as? JsonObject
            ?: error("Pterodactyl 未返回控制台凭据")
        val socket = data.text("socket")
        val address = runCatching { Url(socket) }.getOrNull() ?: error("控制台地址无效")
        require(address.protocol.name == "wss" || (profile.allowHttp && address.protocol.name == "ws")) {
            "控制台需要安全的 WebSocket 地址，请检查 Wings 的 HTTPS 配置"
        }
        require(address.host.isNotBlank() && address.user == null && address.password == null && address.fragment.isEmpty()) { "控制台地址无效" }
        val token = data.text("token").takeIf(String::isNotBlank) ?: error("控制台凭据为空")
        return ConsoleCredentials(address, token)
    }

    private data class ConsoleCredentials(val address: Url, val token: String)

    override fun close() {
        sockets.close()
        api.close()
    }
}

internal fun pterodactylOrigin(base: String): String {
    val origin = Url(base)
    val host = if (':' in origin.host && !origin.host.startsWith("[")) "[${origin.host}]" else origin.host
    val port = if (origin.port != origin.protocol.defaultPort) ":${origin.port}" else ""
    return "${origin.protocol.name}://$host$port"
}
