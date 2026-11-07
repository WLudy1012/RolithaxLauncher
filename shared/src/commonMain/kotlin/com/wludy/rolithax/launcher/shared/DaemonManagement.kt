package com.wludy.rolithax.launcher.shared

import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class DaemonUser(
    val id: String? = null,
    val username: String? = null,
    val name: String? = null,
    val role: String? = null,
    val resources: List<String>? = null,
)

@Serializable
private data class DeleteInstanceRequest(val id: Long, val deleteFiles: Boolean)

@Serializable
private data class CreateUserRequest(val username: String, val password: String, val name: String? = null, val role: String = "user", val resources: List<String> = emptyList())

@Serializable
private data class UpdateUserRequest(val name: String? = null, val password: String? = null, val role: String? = null, val resetApiKey: Boolean = false, val resources: List<String>? = null)

suspend fun DaemonClient.deleteInstance(id: Long, deleteFiles: Boolean = false) {
    deleteInstanceWithMessage(id, deleteFiles)
}

suspend fun DaemonClient.deleteInstanceWithMessage(id: Long, deleteFiles: Boolean = false): String? =
    postResponse("api/instance/delete", json.encodeToString(DeleteInstanceRequest(id, deleteFiles))).message

suspend fun DaemonClient.instanceSettingsPayload(id: Long) = get("api/instance/settings/general/$id")

suspend fun DaemonClient.instanceSettings(id: Long): JsonObject =
    instanceSettingsPayload(id) as? JsonObject ?: throw DaemonException("Daemon 未返回实例设置")

suspend fun DaemonClient.updateInstanceSettings(id: Long, settings: JsonObject) {
    updateInstanceSettingsResponse(id, settings)
}

suspend fun DaemonClient.updateInstanceSettingsResponse(id: Long, settings: JsonObject): DaemonResponse =
    postResponse("api/instance/settings/general/$id", settings.toString())

suspend fun DaemonClient.createInstancePayload(request: JsonObject) =
    postResponse("api/instance/createServer", request.toString()).data

suspend fun DaemonClient.listUsers(): List<DaemonUser> =
    json.decodeFromJsonElement(adminUserListPayload() ?: throw DaemonException("Daemon 未返回用户列表"))

suspend fun DaemonClient.adminUserListPayload() = get("api/admin/user/list")

suspend fun DaemonClient.adminCreateUserWithMessage(body: JsonObject): String? =
    postResponse("api/admin/user/create", body.toString()).message

suspend fun DaemonClient.adminUpdateUserWithMessage(id: String, body: JsonObject): String? =
    postResponse("api/admin/user/update/${id.encodeURLPathPart()}", body.toString()).message

suspend fun DaemonClient.adminDeleteUserWithMessage(id: String): String? =
    postResponse("api/admin/user/delete/${id.encodeURLPathPart()}", "{}").message

suspend fun DaemonClient.createUser(username: String, password: String, name: String?, role: String, resources: List<String> = emptyList()) {
    require(username.isNotBlank() && password.isNotBlank() && role in setOf("user", "admin"))
    post("api/admin/user/create", json.encodeToString(CreateUserRequest(username, password, name, role, resources)))
}

suspend fun DaemonClient.updateUser(id: String, name: String?, password: String?, role: String?, resources: List<String>? = null, resetApiKey: Boolean = false) {
    require(role == null || role in setOf("user", "admin"))
    post("api/admin/user/update/${safeUserId(id)}", json.encodeToString(UpdateUserRequest(name, password, role, resetApiKey, resources)))
}

suspend fun DaemonClient.deleteUser(id: String) {
    post("api/admin/user/delete/${safeUserId(id)}", "{}")
}

private fun safeUserId(id: String): String {
    require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "用户 ID 无效" }
    return id
}

@Serializable
data class DaemonCreateRequest(
    val name: String,
    val core: String,
    val minM: Int,
    val maxM: Int,
    val java: String? = null,
    val args: String? = null,
    val path: String? = null,
    val ignoreEula: Boolean = false,
    val dockerImage: String? = "MSLX://DockerImage/Java/25",
    val dockerPorts: String? = "25565:25565",
    val mcdr: Boolean = false,
    val mcdrPython: String? = null,
    val mcdrHandler: String? = null,
    val mcdrInstall: Boolean = true,
    val mcdrPipMirror: String? = null,
    val coreUrl: String? = null,
    val coreSha256: String? = null,
    val coreMirrors: List<DaemonCoreMirror> = emptyList(),
    val coreFileKey: String? = null,
    val packageUrl: String? = null,
    val packageSha256: String? = null,
    val packageLocalPath: String? = null,
    val packageFileKey: String? = null,
)

@Serializable
data class DaemonCoreMirror(val url: String, val sha256: String? = null)

@Serializable
private data class DaemonCreateResponse(val serverId: String = "")

@Serializable
data class DaemonPluginsMods(
    val totalCount: Int = 0,
    val activeCount: Int = 0,
    val clientOnlyCount: Int = 0,
    val disabledCount: Int = 0,
    val jarFiles: List<String> = emptyList(),
    val clientJarFiles: List<String> = emptyList(),
    val disableJarFiles: List<String> = emptyList(),
)

@Serializable
private data class PluginsModsRequest(val mode: String, val action: String, val targets: List<String>)

suspend fun DaemonClient.createInstance(request: DaemonCreateRequest): String {
    require(request.name.isNotBlank() && request.core.isNotBlank() && request.minM > 0 && request.maxM >= request.minM)
    fun validHash(value: String?): Boolean = value?.length == 64 && value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    require(request.coreUrl.isNullOrBlank() || validHash(request.coreSha256)) { "下载核心需要完整 SHA-256" }
    require(request.packageUrl.isNullOrBlank() || validHash(request.packageSha256)) { "下载安装包需要完整 SHA-256" }
    require(request.coreMirrors.all { validHash(it.sha256 ?: request.coreSha256) }) { "镜像需要完整 SHA-256" }
    val data = post("api/instance/createServer", json.encodeToString(request)) ?: throw DaemonException("Daemon 未返回创建任务")
    return json.decodeFromJsonElement<DaemonCreateResponse>(data).serverId.takeIf { it.isNotBlank() }
        ?: throw DaemonException("Daemon 未返回创建任务 ID")
}

suspend fun DaemonClient.listPluginsMods(id: Long, mode: String): DaemonPluginsMods {
    require(mode in setOf("plugins", "mods"))
    return json.decodeFromJsonElement(get("api/files/pm/instance/$id/list", mapOf("mode" to mode, "checkClient" to "false"))
        ?: throw DaemonException("Daemon 未返回插件或模组列表"))
}

suspend fun DaemonClient.pluginsModsPayload(id: Long, mode: String) =
    getEmptyOnNotFound("api/files/pm/instance/$id/list", mapOf("mode" to mode, "checkClient" to "false"))

suspend fun DaemonClient.setPluginsModsWithMessage(id: Long, body: JsonObject): String? =
    postResponse("api/files/pm/instance/$id/set", body.toString()).message

suspend fun DaemonClient.setPluginsMods(id: Long, mode: String, action: String, targets: List<String>) {
    require(mode in setOf("plugins", "mods") && action in setOf("enable", "disable", "delete") && targets.isNotEmpty())
    post("api/files/pm/instance/$id/set", json.encodeToString(PluginsModsRequest(mode, action, targets)))
}

@Serializable
data class DaemonResourceInstallItem(val projectId: String, val versionId: String, val type: String, val provider: String = "modrinth")

@Serializable
private data class DaemonResourceInstallRequest(val instanceId: Long, val gameVersion: String, val loader: String, val items: List<DaemonResourceInstallItem>)

suspend fun DaemonClient.installResourcesPayload(request: JsonObject) =
    postResponse("api/plugin/mslx-plugin-rolithax/resources/install", request.toString(), allowAny2xx = true).data

suspend fun DaemonClient.resourceTaskPayload(id: String) =
    get("api/plugin/mslx-plugin-rolithax/resources/install/${id.encodeURLPathPart()}")

suspend fun DaemonClient.cancelResourceTaskPayload(id: String) {
    post("api/plugin/mslx-plugin-rolithax/resources/install/${id.encodeURLPathPart()}/cancel", "{}")
}

@Serializable
data class DaemonResourceTask(val id: String, val status: String, val progress: Int = 0, val message: String = "", val installedFiles: List<String> = emptyList())

suspend fun DaemonClient.installResources(id: Long, gameVersion: String, loader: String, items: List<DaemonResourceInstallItem>): DaemonResourceTask {
    require(gameVersion.isNotBlank() && loader.isNotBlank() && items.isNotEmpty() && items.all { it.projectId.isNotBlank() && it.versionId.isNotBlank() && it.type in setOf("mod", "plugin", "modpack", "datapack") })
    val request = DaemonResourceInstallRequest(id, gameVersion, loader, items)
    return json.decodeFromJsonElement<DaemonResourceTask>(post("api/plugin/mslx-plugin-rolithax/resources/install", json.encodeToString(request), allowAccepted = true)
        ?: throw DaemonException("Daemon 未返回安装任务")).checked()
}

suspend fun DaemonClient.resourceTask(id: String): DaemonResourceTask =
    json.decodeFromJsonElement<DaemonResourceTask>(get("api/plugin/mslx-plugin-rolithax/resources/install/${safeUserId(id)}")
        ?: throw DaemonException("Daemon 未返回安装任务")).checked()

private fun DaemonResourceTask.checked(): DaemonResourceTask {
    if (id.isBlank() || status.isBlank()) throw DaemonException("Daemon 返回了无效的安装任务")
    return this
}

suspend fun DaemonClient.cancelResourceTask(id: String) {
    post("api/plugin/mslx-plugin-rolithax/resources/install/${safeUserId(id)}/cancel", "{}")
}

@Serializable
private data class CancelCreationRequest(val serverId: String, val cleanupFiles: Boolean)

suspend fun DaemonClient.cancelCreation(serverId: String, cleanupFiles: Boolean = false) {
    require(serverId.isNotBlank())
    cancelCreationWithMessage(serverId, cleanupFiles)
}

suspend fun DaemonClient.cancelCreationWithMessage(serverId: String, cleanupFiles: Boolean = false): String? =
    postResponse("api/instance/cancelCreation", json.encodeToString(CancelCreationRequest(serverId, cleanupFiles))).message

@Serializable
data class DaemonFrp(val id: Int = 0, val name: String? = null, val service: String? = null)

suspend fun DaemonClient.frpListPayload() = get("api/frp/list")

suspend fun DaemonClient.frpList(): List<DaemonFrp> =
    json.decodeFromJsonElement(frpListPayload() ?: throw DaemonException("Daemon 未返回 FRP 列表"))

@Serializable
data class DaemonJava(val path: String = "", val home: String? = null, val version: String = "", val vendor: String? = null, val is64Bit: Boolean = false)

suspend fun DaemonClient.javaList(): List<DaemonJava> =
    json.decodeFromJsonElement(get("api/java/list", mapOf("refresh" to "false")) ?: throw DaemonException("Daemon 未返回 Java 环境列表"))
