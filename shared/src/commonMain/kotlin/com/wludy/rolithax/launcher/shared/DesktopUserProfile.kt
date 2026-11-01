package com.wludy.rolithax.launcher.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class DaemonProfile(
    val username: String? = null,
    val name: String? = null,
    val avatar: String? = null,
    val role: String? = null,
)

@Serializable
private data class UpdateProfileRequest(
    val username: String? = null,
    val name: String? = null,
    val avatar: String? = null,
    val oldPassword: String? = null,
    val password: String? = null,
    val resetApiKey: Boolean = false,
)

suspend fun DaemonClient.currentUserPayload() = get("api/user/me")

suspend fun DaemonClient.updateSelfWithMessage(body: JsonObject): String? =
    postResponse("api/user/me/update", body.toString()).message

suspend fun DaemonClient.currentProfile(): DaemonProfile =
    json.decodeFromJsonElement(currentUserPayload() ?: throw DaemonException("Daemon 未返回用户信息"))

suspend fun DaemonClient.updateProfile(username: String?, name: String?, avatar: String?, oldPassword: String?, password: String?) {
    require(password.isNullOrBlank() || !oldPassword.isNullOrBlank()) { "修改密码时必须填写当前密码" }
    post("api/user/me/update", json.encodeToString(UpdateProfileRequest(username, name, avatar, oldPassword, password)))
}
