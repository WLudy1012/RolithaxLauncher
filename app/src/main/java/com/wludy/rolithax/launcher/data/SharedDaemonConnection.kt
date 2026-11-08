package com.wludy.rolithax.launcher.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wludy.rolithax.launcher.data.model.InstanceInfo
import com.wludy.rolithax.launcher.data.model.InstanceSummary
import com.wludy.rolithax.launcher.data.model.FileItem
import com.wludy.rolithax.launcher.data.model.LocalJava
import com.wludy.rolithax.launcher.data.model.StatusData
import com.wludy.rolithax.launcher.data.model.CreateServerData
import com.wludy.rolithax.launcher.data.model.CreateServerRequest
import com.wludy.rolithax.launcher.data.model.ServerSettings
import com.wludy.rolithax.launcher.data.model.UpdateSettingsData
import com.wludy.rolithax.launcher.data.model.AdminCreateUserRequest
import com.wludy.rolithax.launcher.data.model.AdminUpdateUserRequest
import com.wludy.rolithax.launcher.data.model.FrpSummary
import com.wludy.rolithax.launcher.data.model.UpdateSelfRequest
import com.wludy.rolithax.launcher.data.model.UserInfo
import com.wludy.rolithax.launcher.data.model.PmListData
import com.wludy.rolithax.launcher.data.model.PmSetRequest
import com.wludy.rolithax.launcher.data.model.ResourceInstallBatchRequest
import com.wludy.rolithax.launcher.data.model.ResourceInstallTaskData
import com.wludy.rolithax.launcher.shared.ConsoleCursor
import com.wludy.rolithax.launcher.shared.ConsoleEvent
import com.wludy.rolithax.launcher.shared.DaemonConsole
import com.wludy.rolithax.launcher.shared.DaemonClient
import com.wludy.rolithax.launcher.shared.DaemonException
import com.wludy.rolithax.launcher.shared.cancelCreationWithMessage
import com.wludy.rolithax.launcher.shared.createInstancePayload
import com.wludy.rolithax.launcher.shared.deleteInstanceWithMessage
import com.wludy.rolithax.launcher.shared.instanceSettingsPayload
import com.wludy.rolithax.launcher.shared.updateInstanceSettingsResponse
import com.wludy.rolithax.launcher.shared.adminCreateUserWithMessage
import com.wludy.rolithax.launcher.shared.adminDeleteUserWithMessage
import com.wludy.rolithax.launcher.shared.adminUpdateUserWithMessage
import com.wludy.rolithax.launcher.shared.adminUserListPayload
import com.wludy.rolithax.launcher.shared.currentUserPayload
import com.wludy.rolithax.launcher.shared.frpListPayload
import com.wludy.rolithax.launcher.shared.updateSelfWithMessage
import com.wludy.rolithax.launcher.shared.cancelResourceTaskPayload
import com.wludy.rolithax.launcher.shared.installResourcesPayload
import com.wludy.rolithax.launcher.shared.pluginsModsPayload
import com.wludy.rolithax.launcher.shared.resourceTaskPayload
import com.wludy.rolithax.launcher.shared.setPluginsModsWithMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

internal class SharedDaemonConnection(
    private val baseUrl: String,
    private val apiKey: String,
    source: OkHttpClient,
) {
    private val lifetime = Job()
    private val transportDispatcher = Dispatcher()
    private val http = HttpClient(OkHttp) {
        engine {
            preconfigured = source.newBuilder().connectionPool(ConnectionPool()).dispatcher(transportDispatcher).build()
            config { dispatcher(transportDispatcher) }
        }
    }
    private val daemon = DaemonClient(baseUrl, apiKey, http, allowHttp = baseUrl.startsWith("http://", true))
    private val gson = Gson()

    private fun jsonObject(value: Any): JsonObject = Json.parseToJsonElement(gson.toJson(value)).jsonObject

    suspend fun verify(): StatusData =
        try {
            request("Daemon 鉴权失败，请检查 API Key") {
                val data = daemon.statusPayload()
                    ?: throw IllegalStateException("Daemon 鉴权失败：未返回有效状态")
                Gson().fromJson(data.toString(), StatusData::class.java)
                    ?: throw IllegalStateException("Daemon 鉴权失败：未返回有效状态")
            }
        } catch (e: IllegalStateException) {
            val status = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<DaemonException>()
                .firstOrNull()?.httpStatus
            if (status == 401 || status == 403) {
                throw IllegalStateException("Daemon 鉴权失败，请检查 API Key", e)
            }
            throw e
        }
    suspend fun status(): StatusData = request("获取状态失败") {
        val data = daemon.statusPayload() ?: throw IllegalStateException("返回数据为空")
        Gson().fromJson(data.toString(), StatusData::class.java) ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun javaList(refresh: Boolean): List<LocalJava> = request("获取 Java 列表失败") {
        val data = daemon.javaListPayload(refresh) ?: return@request emptyList()
        val type = object : TypeToken<List<LocalJava>>() {}.type
        Gson().fromJson<List<LocalJava>>(data.toString(), type) ?: emptyList()
    }

    suspend fun instanceInfo(id: Long): InstanceInfo = request("获取实例信息失败") {
        Gson().fromJson(daemon.instanceInfoPayload(id).toString(), InstanceInfo::class.java)
            ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun listInstances(): List<InstanceSummary> = request("获取实例列表失败") {
        val data = daemon.listInstancesPayload() ?: return@request emptyList()
        val type = object : TypeToken<List<InstanceSummary>>() {}.type
        Gson().fromJson<List<InstanceSummary>>(data.toString(), type) ?: emptyList()
    }

    suspend fun fileList(id: Long, path: String): List<FileItem> = request("获取文件列表失败") {
        val data = daemon.listFilesPayload(id, path) ?: return@request emptyList()
        val type = object : TypeToken<List<FileItem>>() {}.type
        Gson().fromJson<List<FileItem>>(data.toString(), type) ?: emptyList()
    }

    suspend fun fileContent(id: Long, path: String): String = request("读取失败") {
        val data = daemon.fileContentPayload(id, path) ?: throw IllegalStateException("返回数据为空")
        Gson().fromJson(data.toString(), String::class.java) ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun saveFileContent(id: Long, path: String, content: String): String = request("保存失败") {
        daemon.saveFileContentWithMessage(id, path, content) ?: "保存成功"
    }

    suspend fun sendAction(id: Long, action: String): String = request("操作失败") {
        daemon.actionWithMessage(id, action) ?: "操作成功"
    }

    suspend fun createInstance(body: CreateServerRequest): String = request("创建失败") {
        val data = daemon.createInstancePayload(jsonObject(body))
        val created = data?.let { gson.fromJson(it.toString(), CreateServerData::class.java) }
        created?.serverId?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("服务器未返回 ServerId")
    }

    suspend fun deleteInstance(id: Long, deleteFiles: Boolean): String = request("删除实例失败") {
        daemon.deleteInstanceWithMessage(id, deleteFiles) ?: "实例已删除"
    }

    suspend fun cancelCreation(serverId: String, cleanupFiles: Boolean): String = request("取消失败") {
        daemon.cancelCreationWithMessage(serverId, cleanupFiles) ?: "取消信号已发送"
    }

    suspend fun getSettings(id: Long): ServerSettings = request("获取设置失败") {
        val data = daemon.instanceSettingsPayload(id) ?: throw IllegalStateException("返回数据为空")
        gson.fromJson(data.toString(), ServerSettings::class.java) ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun updateSettings(id: Long, settings: ServerSettings): Pair<String, Boolean> = request("保存失败") {
        val response = daemon.updateInstanceSettingsResponse(id, jsonObject(settings))
        val data = response.data?.let { gson.fromJson(it.toString(), UpdateSettingsData::class.java) }
        (response.message ?: "保存成功") to (data?.needListen == true)
    }

    suspend fun userMe(): UserInfo = request("获取用户信息失败") {
        val data = daemon.currentUserPayload() ?: throw IllegalStateException("返回数据为空")
        gson.fromJson(data.toString(), UserInfo::class.java) ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun updateSelf(body: UpdateSelfRequest): String = request("更新用户信息失败", listOf(body.oldPassword, body.password)) {
        redact(daemon.updateSelfWithMessage(jsonObject(body)) ?: "更新成功", listOf(body.oldPassword, body.password))
    }

    suspend fun adminUserList(): List<UserInfo> = request("获取用户列表失败") {
        val data = daemon.adminUserListPayload() ?: return@request emptyList()
        val type = object : TypeToken<List<UserInfo>>() {}.type
        gson.fromJson<List<UserInfo>>(data.toString(), type) ?: emptyList()
    }

    suspend fun adminCreateUser(body: AdminCreateUserRequest): String = request("创建用户失败", listOf(body.password)) {
        redact(daemon.adminCreateUserWithMessage(jsonObject(body)) ?: "创建成功", listOf(body.password))
    }

    suspend fun adminUpdateUser(id: String, body: AdminUpdateUserRequest): String = request("更新用户失败", listOf(body.password)) {
        redact(daemon.adminUpdateUserWithMessage(id, jsonObject(body)) ?: "更新成功", listOf(body.password))
    }

    suspend fun adminDeleteUser(id: String): String = request("删除用户失败") {
        daemon.adminDeleteUserWithMessage(id) ?: "删除成功"
    }

    suspend fun frpList(): List<FrpSummary> = request("获取 FRP 列表失败") {
        val data = daemon.frpListPayload() ?: return@request emptyList()
        val type = object : TypeToken<List<FrpSummary>>() {}.type
        gson.fromJson<List<FrpSummary>>(data.toString(), type) ?: emptyList()
    }

    suspend fun pmList(id: Long, mode: String): PmListData = request("获取列表失败") {
        val data = daemon.pluginsModsPayload(id, mode) ?: return@request PmListData()
        gson.fromJson(data.toString(), PmListData::class.java) ?: PmListData()
    }

    suspend fun pmSet(id: Long, body: PmSetRequest): String = request("操作失败") {
        daemon.setPluginsModsWithMessage(id, jsonObject(body)) ?: "操作成功"
    }

    suspend fun installResources(body: ResourceInstallBatchRequest): ResourceInstallTaskData = request("资源安装任务创建失败") {
        val data = daemon.installResourcesPayload(jsonObject(body)) ?: throw IllegalStateException("Daemon 未返回安装任务")
        gson.fromJson(data.toString(), ResourceInstallTaskData::class.java) ?: throw IllegalStateException("Daemon 未返回安装任务")
    }

    suspend fun resourceInstallTask(id: String): ResourceInstallTaskData = request("读取资源安装进度失败") {
        val data = daemon.resourceTaskPayload(id) ?: throw IllegalStateException("Daemon 未返回安装进度")
        gson.fromJson(data.toString(), ResourceInstallTaskData::class.java) ?: throw IllegalStateException("Daemon 未返回安装进度")
    }

    suspend fun cancelResourceInstall(id: String): Unit = request("取消资源安装失败") {
        daemon.cancelResourceTaskPayload(id)
    }

    private fun redact(message: String, secrets: List<String?>): String =
        secrets.filterNotNull().filter { it.isNotEmpty() }.fold(message) { value, secret -> value.replace(secret, "***") }

    private suspend fun <T> request(fallback: String, secrets: List<String?> = emptyList(), block: suspend () -> T): T = coroutineScope {
        val request = async {
            try {
                block()
            } catch (e: DaemonException) {
                val message = if (e.httpStatus == null && e.businessCode != null && e.businessCode != 200) {
                    e.serverMessage ?: fallback
                } else {
                    e.message ?: fallback
                }
                if (secrets.isEmpty()) throw IllegalStateException(message, e)
                throw IllegalStateException(redact(message, secrets))
            }
        }
        val registration = lifetime.invokeOnCompletion { request.cancel() }
        try { request.await() } finally { registration.dispose() }
    }

    suspend fun runConsole(
        instanceId: Long,
        cursor: ConsoleCursor?,
        onSession: (DaemonConsole) -> Unit,
        onEvent: suspend (ConsoleEvent) -> Unit,
    ) = coroutineScope {
        val console = DaemonConsole(baseUrl, apiKey, http, allowHttp = baseUrl.startsWith("http://", true))
        val stream = launch(start = CoroutineStart.LAZY) { console.run(instanceId, cursor, onEvent) }
        val registration = lifetime.invokeOnCompletion { stream.cancel() }
        try {
            if (!stream.isCancelled) {
                onSession(console)
                stream.start()
            }
            stream.join()
        } finally {
            registration.dispose()
        }
    }

    fun close() {
        lifetime.cancel()
        transportDispatcher.cancelAll()
        daemon.close()
        http.close()
        http.engine.coroutineContext.cancel()
    }
}
