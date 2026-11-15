package com.wludy.rolithax.launcher.shared.panels

import io.ktor.client.HttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*

class McsmPanelClient(private val profile: PanelProfile, http: HttpClient) : PanelClient {
    private val api = PanelHttp(profile, http)
    override val powers = PanelPower.entries.toSet()

    override suspend fun servers(): List<PanelServer> {
        val account = api.request("api/auth", mapOf("advanced" to "true")).jsonObject
        val permission = account.number("permission")?.takeIf { it >= 1 } ?: error("MCSManager 未返回有效用户权限")
        if (permission < 10) {
            return account.items("instances").map { item ->
                val data = item.jsonObject
                server(data, data.text("daemonId"))
            }.filter { profile.nodeId.isBlank() || it.nodeId == profile.nodeId }
        }
        val nodes = if (profile.nodeId.isNotBlank()) listOf(profile.nodeId) else {
            api.request("api/service/remote_services_list").jsonArray.mapNotNull { item ->
                val node = item.jsonObject
                if ((node["available"] as? JsonPrimitive)?.booleanOrNull == false) null
                else node.text("uuid").takeIf(String::isNotBlank)
            }.distinct()
        }
        val result = mutableListOf<PanelServer>()
        for (node in nodes) {
            var page = 1
            do {
                val data = api.request("api/service/remote_service_instances", mapOf(
                    "daemonId" to node, "page" to page.toString(), "page_size" to "50",
                )).jsonObject
                val instances = data.items("data")
                val pages = data.number("maxPage")?.takeIf { it >= 0 } ?: error("MCSManager 未返回分页信息")
                if (instances.isEmpty()) break
                check(data.number("page") == page) { "MCSManager 返回了错误的页码" }
                result += instances.map { server(it.jsonObject, node) }
                page++
            } while (page <= pages)
        }
        return result
    }

    private fun server(data: JsonObject, node: String): PanelServer {
        val id = data.text("instanceUuid")
        require(id.isNotBlank() && node.isNotBlank()) { "MCSManager 返回了无效实例标识" }
        val config = data["config"] as? JsonObject
        val name = config?.text("nickname")?.ifBlank { null } ?: data.text("nickname").ifBlank { id }
        val state = when (data.number("status")) {
            -1 -> "暂不可用"
            0 -> "已停止"
            1 -> "停止中"
            2 -> "启动中"
            3 -> "运行中"
            else -> "状态未知"
        }
        return PanelServer(id, name, state, node)
    }

    private fun query(server: PanelServer): Map<String, String> {
        require(server.id.isNotBlank() && server.nodeId.isNotBlank()) { "实例标识无效" }
        return mapOf("uuid" to server.id, "daemonId" to server.nodeId)
    }

    override suspend fun power(server: PanelServer, action: PanelPower) {
        val operation = when (action) {
            PanelPower.START -> "open"
            PanelPower.STOP -> "stop"
            PanelPower.RESTART -> "restart"
            PanelPower.KILL -> "kill"
        }
        api.request("api/protected_instance/$operation", query(server))
    }

    override suspend fun command(server: PanelServer, command: String) {
        require(command.isNotBlank()) { "命令不能为空" }
        api.request("api/protected_instance/command", query(server) + ("command" to command))
    }

    override suspend fun logs(server: PanelServer, onSnapshot: suspend (String) -> Unit) {
        while (currentCoroutineContext().isActive) {
            val data = api.request("api/protected_instance/outputlog", query(server) + ("size" to "64KB"))
            onSnapshot(data.jsonPrimitive.content.takeLast(65_536))
            delay(2_000)
        }
    }

    override fun close() = api.close()
}
