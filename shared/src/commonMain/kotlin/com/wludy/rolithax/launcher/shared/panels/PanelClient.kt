package com.wludy.rolithax.launcher.shared.panels

import kotlinx.serialization.Serializable

@Serializable
enum class PanelKind(val title: String) {
    MCSL("MCSL Future"), MCSM("MCSManager 10"), PTERODACTYL("Pterodactyl"),
}

@Serializable
data class PanelProfile(
    val id: String,
    val name: String,
    val kind: PanelKind,
    val baseUrl: String,
    val token: String,
    val allowHttp: Boolean = false,
    val nodeId: String = "",
) {
    override fun toString(): String = "PanelProfile(id=$id, kind=$kind, token=[redacted])"
}

data class PanelServer(
    val id: String,
    val name: String,
    val state: String,
    val nodeId: String = "",
    val description: String = "",
)

enum class PanelPower(val title: String) {
    START("启动"), STOP("停止"), RESTART("重启"), KILL("强制终止"),
}

interface PanelClient {
    val powers: Set<PanelPower>
    suspend fun servers(): List<PanelServer>
    suspend fun power(server: PanelServer, action: PanelPower)
    suspend fun command(server: PanelServer, command: String)
    suspend fun logs(server: PanelServer, onSnapshot: suspend (String) -> Unit)
    fun close()
}
