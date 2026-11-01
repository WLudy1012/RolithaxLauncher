package com.wludy.rolithax.launcher.data

import android.util.Base64
import com.wludy.rolithax.launcher.data.model.CommandResultPayload
import com.wludy.rolithax.launcher.data.model.CreateServerRequest
import com.wludy.rolithax.launcher.data.model.ServerCoreClassify
import com.wludy.rolithax.launcher.data.model.ServerCoreDownloadInfo
import com.wludy.rolithax.launcher.data.model.ServerCoreGameVersion
import com.wludy.rolithax.launcher.data.model.AdminCreateUserRequest
import com.wludy.rolithax.launcher.data.model.AdminUpdateUserRequest
import com.wludy.rolithax.launcher.data.model.FrpSummary
import com.wludy.rolithax.launcher.data.model.FileItem
import com.wludy.rolithax.launcher.data.model.InstanceInfo
import com.wludy.rolithax.launcher.data.model.InstanceSummary
import com.wludy.rolithax.launcher.data.model.LocalJava
import com.wludy.rolithax.launcher.data.model.PmListData
import com.wludy.rolithax.launcher.data.model.PmSetRequest
import com.wludy.rolithax.launcher.data.model.SaveUploadRequest
import com.wludy.rolithax.launcher.data.model.ServerSettings
import com.wludy.rolithax.launcher.data.model.StatusData
import com.wludy.rolithax.launcher.data.model.UploadFinishRequest
import com.wludy.rolithax.launcher.data.model.UpdateSelfRequest
import com.wludy.rolithax.launcher.data.model.ResourceInstallBatchRequest
import com.wludy.rolithax.launcher.data.model.ResourceInstallTaskData
import com.wludy.rolithax.launcher.data.model.UserInfo
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.wludy.rolithax.launcher.data.remote.ApiClient
import com.wludy.rolithax.launcher.data.remote.ConsoleHubClient
import com.wludy.rolithax.launcher.data.remote.DaemonApi
import com.wludy.rolithax.launcher.data.remote.SystemMonitorClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

private const val MAX_INSTANCE_ICON_BYTES = 2 * 1024 * 1024

/** 实例相关的数据入口，封装 REST 调用与 SignalR 控制台客户端创建。 */
class InstanceRepository {

    @Volatile
    private var api: DaemonApi? = null

    @Volatile
    private var httpClient: OkHttpClient? = null

    @Volatile
    private var sharedConnection: SharedDaemonConnection? = null

    private val hubClients = CopyOnWriteArrayList<com.wludy.rolithax.launcher.data.remote.ReconnectingHubClient>()

    @Volatile
    var baseUrl: String = ""
        private set

    @Volatile
    var apiKey: String = ""
        private set

    val isConfigured: Boolean get() = api != null && baseUrl.isNotBlank()

    /**
     * 配置连接地址并重建 API 客户端(每次连接/切换连接时调用)。
     *
     * 配置未变化时直接早退：心跳（每 5 秒）等调用方会重复调用本方法，
     * 无条件重建 Retrofit/OkHttpClient 会丢弃连接池与线程资源，造成周期性卡顿。
     */
    fun configure(baseUrl: String, apiKey: String, allowHttp: Boolean = false) {
        val normalized = ApiClient.normalizeDaemonUrl(baseUrl, allowHttp)
        val trimmedKey = apiKey.trim()
        if (api != null && normalized == this.baseUrl && trimmedKey == this.apiKey) return
        closeResources()
        this.baseUrl = normalized
        this.apiKey = trimmedKey
        if (normalized.isBlank()) {
            this.api = null
            return
        }
        val connection = ApiClient.buildDaemonConnection(normalized, this.apiKey)
        this.api = connection.api
        this.httpClient = connection.client
        this.sharedConnection = SharedDaemonConnection(normalized, this.apiKey, connection.client)
        AppLogger.i("Repository", "configure: ${normalized.trimEnd('/')} allowHttp=$allowHttp")
    }

    /** 关闭本 Daemon 的 Hub、OkHttp 调度器、连接池和缓存。 */
    fun close() {
        closeResources()
        api = null
        baseUrl = ""
        apiKey = ""
    }

    private fun closeResources() {
        hubClients.forEach { runCatching { it.close() } }
        hubClients.clear()
        sharedConnection?.close()
        sharedConnection = null
        httpClient?.let { client ->
            runCatching { client.dispatcher.cancelAll() }
            runCatching { client.dispatcher.executorService.shutdown() }
            runCatching { client.connectionPool.evictAll() }
            runCatching { client.cache?.close() }
        }
        httpClient = null
    }

    fun trackHub(client: com.wludy.rolithax.launcher.data.remote.ReconnectingHubClient) {
        hubClients += client
    }

    private fun requireApi(): DaemonApi =
        api ?: throw IllegalStateException("尚未配置连接信息")

    private suspend fun <T> daemonResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        Result.failure(IllegalStateException(
                ApiClient.errorMessageFrom(e) ?: "HTTP ${e.code()} 请求失败",
                e,
            ))
    } catch (e: Throwable) {
        Result.failure(e)
    }

    suspend fun verify(): Result<Unit> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).verify()
        AppLogger.i("Repository", "verify 成功")
    }

    suspend fun getStatus(): Result<StatusData> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).status()
    }

    suspend fun javaList(refresh: Boolean = false): Result<List<LocalJava>> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).javaList(refresh)
    }

    /** 从 MSLAPI 获取在线 Java 版本，失败时回退到 Microsoft OpenJDK。 */
    suspend fun onlineJavaVersions(os: String, arch: String): Result<List<String>> = runCatching {
        val mslVersions = runCatching {
            val response = ApiClient.buildMslJavaApi().jdkVersions(os, arch)
            if (response.code == 200) response.data.orEmpty() else emptyList()
        }.getOrDefault(emptyList())

        if (mslVersions.isNotEmpty()) {
            return@runCatching normalizeJavaVersions(mslVersions)
        }

        val microsoftVersions = ApiClient.buildMicrosoftJavaApi()
            .releases()
            .asSequence()
            .mapNotNull { release ->
                Regex("(?:jdk|java|microsoft)[-_]?(\\d+)", RegexOption.IGNORE_CASE)
                    .find(release.tag_name.orEmpty())
                    ?.groupValues
                    ?.getOrNull(1)
            }
            .toList()
        normalizeJavaVersions(microsoftVersions).ifEmpty {
            throw IllegalStateException("MSLAPI 和微软官方均未返回 Java 版本")
        }
    }

    private fun normalizeJavaVersions(versions: List<String>): List<String> = versions
        .filter { it.isNotBlank() }
        .distinct()
        .sortedWith(compareByDescending { it.toIntOrNull() ?: 0 })

    suspend fun listInstances(): Result<List<InstanceSummary>> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).listInstances()
    }

    suspend fun instanceInfo(id: Long): Result<InstanceInfo> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).instanceInfo(id)
    }

    suspend fun installResources(request: ResourceInstallBatchRequest): Result<ResourceInstallTaskData> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).installResources(request)
    }

    suspend fun resourceInstallTask(id: String): Result<ResourceInstallTaskData> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).resourceInstallTask(id)
    }

    suspend fun cancelResourceInstall(id: String): Result<Unit> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).cancelResourceInstall(id)
    }

    suspend fun createInstance(request: CreateServerRequest): Result<String> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).createInstance(request)
    }

    suspend fun deleteInstance(id: Long, deleteFiles: Boolean): Result<String> = daemonResult {
        AppLogger.i("Repository", "删除实例 id=$id deleteFiles=$deleteFiles")
        val message = (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).deleteInstance(id, deleteFiles)
        AppLogger.i("Repository", "删除实例成功 id=$id")
        message
    }

    suspend fun cancelCreation(serverId: String, cleanupFiles: Boolean = false): Result<String> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).cancelCreation(serverId, cleanupFiles)
    }

    suspend fun deleteUpload(uploadId: String): Result<Unit> = daemonResult {
        val response = requireApi().deleteUpload(uploadId)
        if (response.code != 200) throw IllegalStateException(response.message ?: "清理上传失败")
    }

    /**
     * 流式分块上传一个文件，返回可用于 createServer 的 fileKey(uploadId)。
     * 从 input 流逐块读取(每块 10MB)上传，避免 readBytes() 把整个文件读入内存；
     * 流读取在 IO 线程执行，避免阻塞主线程。
     *
     * 任一分片或合并失败都会调用 [deleteUpload] 清理服务端已落盘的临时分片，
     * 避免失败重试在 Daemon 上不断堆积孤儿碎片占用磁盘。
     */
    suspend fun uploadFileStream(
        input: () -> java.io.InputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit,
    ): Result<String> = try {
        val uploadId = uploadInit().getOrThrow()
        try {
            val chunkSize = 10 * 1024 * 1024
            val buffer = ByteArray(chunkSize)
            var index = 0
            var uploaded = 0L
            withContext(Dispatchers.IO) {
                input().use { stream ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        val chunk = buffer.copyOf(read)
                        uploadChunk(uploadId, index, chunk).getOrThrow()
                        index++
                        uploaded += read
                        onProgress(((uploaded * 100) / maxOf(totalBytes, 1L)).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (index == 0) {
                // 空文件也至少传一个空块，保证 finish 时总块数 >= 1
                uploadChunk(uploadId, 0, byteArrayOf()).getOrThrow()
                index = 1
            }
            uploadFinish(uploadId, index).getOrThrow()
            Result.success(uploadId)
        } catch (e: Exception) {
            AppLogger.w("Repository", "上传失败，清理服务端临时分片 uploadId=$uploadId", e)
            withContext(NonCancellable) {
                val cleaned = withTimeoutOrNull(5_000) {
                    deleteUpload(uploadId).onFailure {
                        AppLogger.w("Repository", "清理服务端临时分片失败 uploadId=$uploadId", it)
                    }
                }
                if (cleaned == null) AppLogger.w("Repository", "清理服务端临时分片超时 uploadId=$uploadId")
            }
            throw e
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun serverCoreClassify(): Result<ServerCoreClassify> = runCatching {
        val response = ApiClient.buildMslServerCoreApi().classify()
        if (response.code != 200) throw IllegalStateException(response.message ?: "获取核心分类失败")
        val element = response.data ?: throw IllegalStateException("返回分类为空")
        val obj: JsonObject = when {
            element.isJsonObject -> element.asJsonObject
            element.isJsonArray -> {
                val arr = element.asJsonArray
                if (arr.size() == 0) throw IllegalStateException("返回分类为空")
                arr.get(0).asJsonObject
            }
            else -> throw IllegalStateException("返回分类格式错误")
        }
        Gson().fromJson(obj, ServerCoreClassify::class.java)
    }

    suspend fun serverCoreGameVersion(name: String): Result<ServerCoreGameVersion> = runCatching {
        val response = ApiClient.buildMslServerCoreApi().gameVersion(name)
        if (response.code != 200) throw IllegalStateException(response.message ?: "获取核心版本失败")
        val element = response.data ?: throw IllegalStateException("返回版本为空")
        val obj: JsonObject = when {
            element.isJsonObject -> element.asJsonObject
            element.isJsonArray -> {
                val arr = element.asJsonArray
                if (arr.size() == 0) throw IllegalStateException("返回版本为空")
                arr.get(0).asJsonObject
            }
            else -> throw IllegalStateException("返回版本格式错误")
        }
        Gson().fromJson(obj, ServerCoreGameVersion::class.java)
    }

    suspend fun serverCoreBuilds(name: String, version: String): Result<List<String>> = runCatching {
        val response = ApiClient.buildMslServerCoreApi().builds(name, version)
        if (response.code != 200) throw IllegalStateException(response.message ?: "获取构建版本失败")
        response.data.orEmpty()
    }

    suspend fun serverCoreDownloadInfo(name: String, version: String, build: String = "latest"): Result<ServerCoreDownloadInfo> = runCatching {
        val response = ApiClient.buildMslServerCoreApi().downloadInfo(name, version, build)
        if (response.code != 200) throw IllegalStateException(response.message ?: "获取下载信息失败")
        response.data ?: throw IllegalStateException("返回下载信息为空")
    }

    suspend fun sendAction(id: Long, action: String): Result<String> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).sendAction(id, action)
    }

    suspend fun getSettings(id: Long): Result<ServerSettings> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).getSettings(id)
    }

    suspend fun updateSettings(id: Long, settings: ServerSettings): Result<Pair<String, Boolean>> = daemonResult {
        val normalized = settings.copy(java = normalizeJavaConfig(settings.java))
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).updateSettings(id, normalized)
    }

    private fun normalizeJavaConfig(java: String): String {
        val version = java.removePrefix("MSLX://Java/").trim()
        return if (version.isNotBlank() && java.startsWith("MSLX://Java/")) {
            "MSLX://Java/$version"
        } else {
            java.trim()
        }
    }

    suspend fun updateSelf(body: UpdateSelfRequest): Result<String> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).updateSelf(body)
    }

    suspend fun adminUserList(): Result<List<UserInfo>> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).adminUserList()
    }

    suspend fun adminCreateUser(body: AdminCreateUserRequest): Result<String> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).adminCreateUser(body)
    }

    suspend fun adminUpdateUser(id: String, body: AdminUpdateUserRequest): Result<String> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).adminUpdateUser(id, body)
    }

    suspend fun adminDeleteUser(id: String): Result<String> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).adminDeleteUser(id)
    }

    suspend fun frpList(): Result<List<FrpSummary>> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).frpList()
    }

    suspend fun pmList(id: Long, mode: String): Result<PmListData> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).pmList(id, mode)
    }

    suspend fun pmSet(id: Long, mode: String, action: String, targets: List<String>): Result<String> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).pmSet(id, PmSetRequest(mode, action, targets))
    }

    suspend fun fileContent(id: Long, path: String): Result<String> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).fileContent(id, path)
    }

    /** 列出实例目录下的文件/子目录（path 为空表示实例根目录）。 */
    suspend fun fileList(id: Long, path: String = ""): Result<List<FileItem>> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).fileList(id, path)
    }

    suspend fun saveFileContent(id: Long, path: String, content: String): Result<String> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).saveFileContent(id, path, content)
    }

    suspend fun userMe(): Result<UserInfo> = daemonResult {
        (sharedConnection ?: throw IllegalStateException("尚未配置连接信息")).userMe()
    }

    suspend fun uploadInit(): Result<String> = daemonResult {
        val resp = requireApi().uploadInit()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "初始化上传失败")
        resp.data?.uploadId ?: throw IllegalStateException("未返回 uploadId")
    }

    suspend fun uploadChunk(uploadId: String, index: Int, bytes: ByteArray): Result<Unit> = daemonResult {
        val part = MultipartBody.Part.createFormData(
            "file",
            "chunk_$index",
            bytes.toRequestBody("application/octet-stream".toMediaType()),
        )
        val resp = requireApi().uploadChunk(uploadId, index, part)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "上传分片失败")
    }

    suspend fun uploadFinish(uploadId: String, totalChunks: Int): Result<String> = daemonResult {
        val resp = requireApi().uploadFinish(uploadId, UploadFinishRequest(totalChunks))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "合并分片失败")
        resp.message ?: "上传成功"
    }

    suspend fun saveUpload(id: Long, uploadId: String, fileName: String, currentPath: String): Result<String> = daemonResult {
        val resp = requireApi().saveUpload(id, SaveUploadRequest(uploadId, fileName, currentPath))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "保存文件失败")
        resp.message ?: "保存成功"
    }

    fun createConsoleClient(
        instanceId: Long,
        onLog: (String) -> Unit,
        onCommandResult: (CommandResultPayload) -> Unit,
        onEulaRequired: () -> Unit,
    ): ConsoleHubClient = ConsoleHubClient(baseUrl, apiKey, instanceId, onLog, onCommandResult, onEulaRequired)
        .also { hubClients += it }

    /** 创建系统负载监视客户端(订阅 /api/hubs/system 的 ReceiveSystemStats)。 */
    fun createSystemMonitorClient(
        onStats: (com.wludy.rolithax.launcher.data.model.NodeStatsPayload) -> Unit,
    ): SystemMonitorClient = SystemMonitorClient(baseUrl, apiKey, onStats)
        .also { hubClients += it }

    // ---------------------------------------------------------------- 实例图标

    /** 第三方状态 API 客户端（懒建，避免每个实例重复创建 OkHttpClient）。 */
    private val mcsrvstatApi by lazy { ApiClient.buildMcsrvstatApi() }
    private val mcstatusApi by lazy { ApiClient.buildMcstatusApi() }

    /**
     * 取实例图标（PNG 字节），三级来源依次回退：
     * 1. Daemon 内置端点（实例目录 server-icon.png）；
     * 2. 图标插件（Daemon 侧拉取 + 磁盘缓存 + 第三方查询）；
     * 3. App 直连第三方状态 API（仅当实例在 server.properties 中显式配置了公网地址）。
     * 全部无果返回 null，由调用方展示占位图；缓存策略见 [InstanceIconStore]。
     */
    suspend fun fetchInstanceIcon(id: Long): ByteArray? {
        fetchIconBytes { requireApi().instanceIcon(id) }?.let { return it }
        fetchIconBytes { requireApi().pluginServerIcon(id) }?.let { return it }
        val address = resolvePublicAddress(id) ?: return null
        return fetchThirdPartyIcon(address)
    }

    /** 执行一次图标请求：仅接受 2xx 且 Content-Type 为图片的响应（无图标时端点以 JSON + 4xx 承载业务码）。 */
    private suspend fun fetchIconBytes(call: suspend () -> Response<ResponseBody>): ByteArray? =
        runCatching {
            val response = call()
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                return@runCatching null
            }
            if (!response.headers()["Content-Type"].orEmpty().startsWith("image/")) {
                response.body()?.close()
                return@runCatching null
            }
            response.body()?.use(::readLimitedIcon)
        }.getOrNull()

    private fun readLimitedIcon(body: ResponseBody): ByteArray? {
        if (body.contentLength() > MAX_INSTANCE_ICON_BYTES) return null
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count > MAX_INSTANCE_ICON_BYTES - total) return@use null
                output.write(buffer, 0, count)
                total += count
            }
            output.toByteArray().takeIf { it.isNotEmpty() }
        }
    }

    /** 第三方状态 API：mcsrvstat.us → mcstatus.io 回退，仅取 online + icon 两个字段。 */
    private suspend fun fetchThirdPartyIcon(address: String): ByteArray? {
        val primary = runCatching {
            val resp = mcsrvstatApi.status(address)
            if (resp.online) decodeIconDataUri(resp.icon) else null
        }.getOrNull()
        if (primary != null) return primary
        return runCatching {
            val resp = mcstatusApi.status(address)
            if (resp.online) decodeIconDataUri(resp.icon) else null
        }.getOrNull()
    }

    /** `data:image/png;base64,xxxx` 形式解码为 PNG 字节（格式不符返回 null）。 */
    private fun decodeIconDataUri(dataUri: String?): ByteArray? {
        if (dataUri.isNullOrBlank()) return null
        val marker = dataUri.indexOf("base64,", ignoreCase = true)
        if (marker < 0) return null
        val payload = dataUri.substring(marker + "base64,".length)
        if (payload.length > (MAX_INSTANCE_ICON_BYTES + 2) / 3 * 4 + 4) return null
        return runCatching { Base64.decode(payload, Base64.DEFAULT).takeIf { it.size <= MAX_INSTANCE_ICON_BYTES } }
            .getOrNull()
    }

    /**
     * 从 server.properties 解析可外发查询的公网地址（`host` 或 `host:port`）。
     * 未配置 server-ip / 私网地址 / 属性读取失败时返回 null（不发外网请求）。
     */
    private suspend fun resolvePublicAddress(id: Long): String? {
        val text = fileContent(id, "server.properties").getOrNull() ?: return null
        var host: String? = null
        var port = 25565
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val split = line.indexOf('=')
            if (split <= 0) return@forEach
            when (line.substring(0, split).trim()) {
                "server-ip" -> host = line.substring(split + 1).trim()
                "server-port" -> line.substring(split + 1).trim().toIntOrNull()
                    ?.takeIf { it in 1..65535 }?.let { port = it }
            }
        }
        val target = host?.takeIf { it.isNotBlank() } ?: return null
        if (!isPublicHost(target)) return null
        return if (port == 25565) target else "$target:$port"
    }

    /** 仅公网地址允许外发：排除私网/回环/链路本地/组播 IPv4 与内网域名（与 Daemon 图标插件同口径）。 */
    private fun isPublicHost(host: String): Boolean {
        if (host.contains(':')) {
            val lower = host.lowercase()
            return !(lower == "::1" || lower.startsWith("fe80") || lower.startsWith("fc") || lower.startsWith("fd"))
        }
        if (host.any { it.isLetter() }) {
            val lower = host.lowercase()
            if (lower == "localhost" || lower.endsWith(".localhost")) return false
            return listOf(".local", ".lan", ".home", ".internal", ".intranet", ".localdomain")
                .none { lower.endsWith(it) }
        }
        val parts = host.split('.')
        if (parts.size != 4) return false
        val bytes = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return false }
        return !(
            bytes[0] == 10 ||
                bytes[0] == 127 ||
                (bytes[0] == 172 && bytes[1] in 16..31) ||
                (bytes[0] == 192 && bytes[1] == 168) ||
                (bytes[0] == 169 && bytes[1] == 254) ||
                bytes[0] == 0 ||
                bytes[0] >= 224
            )
    }
}
