package com.wludy.rolithax.launcher.ui.connect

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wludy.rolithax.launcher.RolithaxApplication
import com.wludy.rolithax.launcher.data.DaemonConfig
import com.wludy.rolithax.launcher.data.model.PairCodeData
import com.wludy.rolithax.launcher.data.model.PairCodeRequest
import com.wludy.rolithax.launcher.data.model.PairRedeemRequest
import com.wludy.rolithax.launcher.data.remote.ApiClient
import com.wludy.rolithax.launcher.data.remote.DaemonApi
import com.wludy.rolithax.launcher.data.remote.PairingPayloadCodec
import com.wludy.rolithax.launcher.data.remote.pairingEndpoint
import com.wludy.rolithax.launcher.data.remote.samePairingEndpoint
import com.wludy.rolithax.launcher.shared.schemaCapabilities
import com.wludy.rolithax.launcher.shared.DaemonException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.UUID

data class ConnectUiState(
    val editingId: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val endpointsText: String = "",
    val apiKey: String = "",
    val allowHttp: Boolean = false,
    val initializing: Boolean = false,
    val loading: Boolean = false,
    val autoChecking: Boolean = false,
    val error: String? = null,
    val pairing: Boolean = false,
    val pairingReady: Boolean = false,
    val confirmHttpPairing: Boolean = false,
    /** 正在生成配对二维码。 */
    val generating: Boolean = false,
    /** 生成成功的配对码（非空时界面弹二维码窗）。 */
    val pairCode: PairCodeData? = null,
    val pluginNotice: String? = null,
)

class ConnectViewModel(
    application: Application,
    private val autoConnect: Boolean = true,
    private val editingDaemonId: String? = null,
) : AndroidViewModel(application) {

    private val container = getApplication<RolithaxApplication>().container
    private val registry = container.daemonRegistry
    private val store = container.settingsStore
    private data class PendingPairing(val contents: String, val url: String, val expiresAt: Long)
    private var pendingPairing: PendingPairing? = null

    private val _state = MutableStateFlow(ConnectUiState(initializing = autoConnect || editingDaemonId != null))
    val state = _state.asStateFlow()

    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val connected = _connected.asSharedFlow()

    /** 启动自动连接失败事件（携带错误信息），由界面回退主页并弹窗提示。 */
    private val _autoConnectFailed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val autoConnectFailed = _autoConnectFailed.asSharedFlow()

    init {
        if (autoConnect || editingDaemonId != null) {
            viewModelScope.launch {
                val settings = try {
                    store.settingsFlow.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.wludy.rolithax.launcher.data.AppLogger.w("Connect", "读取设置失败", e)
                    null
                }
                val target = if (editingDaemonId == null) settings?.activeDaemon
                else settings?.daemons?.firstOrNull { it.id == editingDaemonId }
                if (target != null) {
                    _state.update {
                        it.copy(
                            editingId = target.id,
                            name = target.name,
                            baseUrl = target.baseUrl,
                            endpointsText = target.endpoints.joinToString("\n"),
                            apiKey = target.apiKey,
                            allowHttp = target.allowHttp,
                            initializing = false,
                        )
                    }
                    // 已有激活的 Daemon → 自动连接
                    if (autoConnect) doConnect(target, auto = true)
                } else {
                    _state.update {
                        it.copy(
                            initializing = false,
                            error = when {
                                settings == null -> "无法读取已保存的 Daemon 连接。"
                                editingDaemonId != null -> "此 Daemon 连接已被移除。"
                                else -> null
                            },
                        )
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }
    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrl = value, pairingReady = false, error = null) }
    fun onEndpointsChange(value: String) = _state.update { it.copy(endpointsText = value, error = null) }
    fun onApiKeyChange(value: String) = _state.update { it.copy(apiKey = value, pairingReady = false, error = null) }
    fun onAllowHttpChange(value: Boolean) = _state.update { it.copy(allowHttp = value, pairingReady = false, error = null) }

    fun connect() {
        val s = _state.value
        if (s.initializing || s.loading || s.pairing || s.confirmHttpPairing || s.generating) return
        if (editingDaemonId != null && s.editingId == null) return
        val baseUrl = normalizeBaseUrl(s.baseUrl, s.allowHttp)
        val apiKey = s.apiKey.trim()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            _state.update { it.copy(error = "请填写完整的 Daemon 地址和 API Key。") }
            return
        }
        val name = s.name.trim().ifBlank { baseUrl }
        val endpoints = s.endpointsText
            .lineSequence()
            .map { normalizeBaseUrl(it, s.allowHttp) }
            .filter { it.isNotBlank() && !it.equals(baseUrl, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .toList()
        val config = DaemonConfig(
            id = s.editingId ?: UUID.randomUUID().toString(),
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            allowHttp = s.allowHttp,
            endpoints = endpoints,
        )
        doConnect(config)
    }

    /**
     * 规范化 Daemon 地址：默认强制 HTTPS（http 自动升级）；用户勾选"允许 HTTP"后保留明文地址。
     */
    private fun normalizeBaseUrl(input: String, allowHttp: Boolean): String =
        ApiClient.normalizeDaemonUrl(input, allowHttp)

    private fun doConnect(config: DaemonConfig, auto: Boolean = false) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, autoChecking = auto, error = null) }
        viewModelScope.launch {
            val settings = runCatching { store.settingsFlow.first() }
                .onFailure { com.wludy.rolithax.launcher.data.AppLogger.w("Connect", "读取设置失败", it) }
                .getOrNull()
            if (editingDaemonId != null && settings?.daemons?.none { it.id == editingDaemonId } != false) {
                _state.update { it.copy(loading = false, autoChecking = false, error = "无法读取此 Daemon 连接。") }
                return@launch
            }
            val duplicate = settings?.daemons?.any {
                it.id != config.id &&
                    (listOf(it.baseUrl) + it.endpoints).map { endpoint ->
                        ApiClient.normalizeDaemonUrl(endpoint, it.allowHttp)
                    }.any { existing ->
                        (listOf(config.baseUrl) + config.endpoints).any { candidate ->
                            existing.equals(
                                ApiClient.normalizeDaemonUrl(candidate, config.allowHttp),
                                ignoreCase = true,
                            )
                        }
                    } &&
                    it.apiKey == config.apiKey
            } == true
            if (duplicate) {
                _state.update { it.copy(loading = false, autoChecking = false, error = "同一 API Key 已连接此 Daemon，不能重复添加。") }
                return@launch
            }
            val result = try {
                val verified = registry.verifyConnection(config)
                store.upsertDaemon(config)
                registry.syncVerified(store.settingsFlow.first(), config, verified)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (result.isSuccess) {
                if (auto) finishConnection()
                else {
                    val notice = withTimeoutOrNull(4_000) {
                        val daemon = registry.repositoryFor(config.id)?.consoleConnection?.daemon
                        try {
                            val capabilities = daemon?.schemaCapabilities() as? JsonObject
                            val version = capabilities?.get("pluginVersion")?.jsonPrimitive?.contentOrNull
                            val protocol = capabilities?.get("protocolVersion")?.jsonPrimitive?.intOrNull
                            when {
                                daemon == null -> "已连接。暂时无法读取扩展版本。"
                                capabilities == null -> "已连接。未发现支持 Schema 3 的 Rolithax 扩展，基础管理可用。"
                                protocol != 1 -> "已连接。扩展版本 ${version ?: "未知"} 的 Schema 协议不兼容。"
                                else -> "已连接。Rolithax 扩展 ${version ?: "版本未知"}，协议 $protocol。"
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: DaemonException) {
                            if (error.httpStatus == 401 || error.httpStatus == 403)
                                "已连接。当前凭据无权读取扩展能力。"
                            else "已连接。暂时无法读取扩展版本。"
                        } catch (_: Exception) { "已连接。暂时无法读取扩展版本。" }
                    } ?: "已连接。扩展版本检查超时。"
                    _state.update { it.copy(loading = false, autoChecking = false, pluginNotice = notice) }
                }
            } else {
                val message = "连接失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                _state.update {
                    it.copy(loading = false, autoChecking = false, error = message)
                }
                // 启动自动连接失败：通知界面回退主页并弹窗，而不是停留在连接页卡住
                if (auto) {
                    _autoConnectFailed.tryEmit(message)
                }
            }
        }
    }

    fun finishConnection() {
        _state.update { it.copy(loading = false, autoChecking = false, pluginNotice = null) }
        _connected.tryEmit(Unit)
    }

    fun onScanResult(contents: String?) {
        if (contents.isNullOrBlank()) return
        submitPairingPayload(contents)?.let { message ->
            _state.update { it.copy(error = message) }
        }
    }

    fun submitPairingPayload(contents: String): String? {
        val state = _state.value
        if (state.initializing || state.loading || state.pairing || state.confirmHttpPairing || state.generating) {
            return "请等待当前连接操作完成。"
        }
        val text = contents.trim()
        val payload = PairingPayloadCodec.decode(text)
            ?: return "无法识别配对内容。请扫描插件生成的二维码，或粘贴「复制原文」得到的完整内容。"
        val url = payload.url?.trim().orEmpty()
        if (payload.version != 1 || url.isBlank() || payload.code.isNullOrBlank()) {
            return "配对内容无效或不完整，请在插件中重新生成。"
        }
        val endpoint = pairingEndpoint(url)
            ?: return "配对内容中的 Daemon 地址无效，请在插件中重新生成。"
        if (payload.expiresAt > 0 && payload.expiresAt <= System.currentTimeMillis() / 1000L) {
            return "配对内容已过期，请在插件中重新生成。"
        }
        val pending = PendingPairing(text, endpoint.toString(), payload.expiresAt)
        if (endpoint.scheme == "http") {
            pendingPairing = pending
            _state.update { it.copy(confirmHttpPairing = true, error = null) }
        } else {
            redeemPairing(pending)
        }
        return null
    }

    fun confirmHttpPairing() {
        val pending = pendingPairing ?: return
        pendingPairing = null
        _state.update { it.copy(confirmHttpPairing = false) }
        redeemPairing(pending)
    }

    fun cancelHttpPairing() {
        pendingPairing = null
        _state.update { it.copy(confirmHttpPairing = false) }
    }

    private fun redeemPairing(pending: PendingPairing) {
        if (pending.expiresAt > 0 && pending.expiresAt <= System.currentTimeMillis() / 1000L) {
            _state.update { it.copy(error = "配对内容已过期，请在插件中重新生成。") }
            return
        }
        _state.update { it.copy(pairing = true, pairingReady = false, error = null) }

        viewModelScope.launch {
            var previousKey: String? = null
            try {
                val app = getApplication<Application>()
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android 设备" }
                val fingerprint = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
                    ?.takeIf { it.isNotBlank() }?.let { "android-$it" }
                previousKey = findPreviousApiKey(pending.url)
                val request = PairRedeemRequest(pending.contents, deviceName, fingerprint, previousKey)
                val resp = withPairingConnection(pending.url, "") { it.pairRedeem(request) }
                val data = resp.data
                if (resp.code != 200 || data?.apiKey.isNullOrBlank()) {
                    throw IllegalStateException(resp.message ?: "服务端未返回有效凭据")
                }
                val daemonUrl = data.daemonUrl?.takeIf { it.isNotBlank() } ?: pending.url
                if (!samePairingEndpoint(daemonUrl, pending.url)) {
                    throw IllegalStateException("服务端返回的 Daemon 地址与配对地址不一致")
                }
                val isHttp = pairingEndpoint(daemonUrl)?.scheme == "http"
                _state.update {
                    it.copy(
                        name = it.name.trim().ifBlank { deviceName },
                        baseUrl = daemonUrl,
                        endpointsText = "",
                        apiKey = data.apiKey,
                        allowHttp = isHttp,
                        pairing = false,
                        pairingReady = true,
                        error = null,
                    )
                }
                com.wludy.rolithax.launcher.data.AppLogger.i("Connect", "配对成功 role=${data.role} 凭据到期 ${data.expiresAt}")
                if (!isHttp) connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = when ((e as? HttpException)?.code()) {
                    404 -> "服务端未安装 Rolithax 插件，或插件版本过旧。"
                    else -> ApiClient.errorMessageFrom(e) ?: e.message ?: "网络异常"
                }
                val redactedDetail = detail.replace(pending.contents, "***")
                val safeDetail = previousKey?.takeIf { it.isNotEmpty() }?.let { redactedDetail.replace(it, "***") }
                    ?: redactedDetail
                com.wludy.rolithax.launcher.data.AppLogger.w("Connect", "配对失败：$safeDetail")
                _state.update { it.copy(pairing = false, error = "配对失败：$safeDetail") }
            }
        }
    }

    /** 生成一次性配对二维码（使用当前表单里的管理员凭据，供另一台设备扫码接入）。 */
    fun createPairCode() {
        val s = _state.value
        if (s.initializing || s.loading || s.pairing || s.confirmHttpPairing || s.generating) return
        if (editingDaemonId != null && s.editingId == null) return
        val baseUrl = normalizeBaseUrl(s.baseUrl, s.allowHttp)
        val apiKey = s.apiKey.trim()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            _state.update { it.copy(error = "请先填写 Daemon 地址与 API Key，再生成配对二维码。") }
            return
        }
        _state.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            try {
                val resp = withPairingConnection(baseUrl, apiKey) { it.pairCreateCode(PairCodeRequest()) }
                val data = resp.data
                if (resp.code != 200 || data?.payload.isNullOrBlank()) {
                    throw IllegalStateException(resp.message ?: "服务端未返回配对码")
                }
                _state.update { it.copy(generating = false, pairCode = data) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = when ((e as? HttpException)?.code()) {
                    404 -> "服务端未安装 Rolithax 插件，或插件版本过旧。"
                    403 -> "当前账号没有生成配对码的权限。"
                    else -> ApiClient.errorMessageFrom(e) ?: e.message ?: "网络异常"
                }
                _state.update { it.copy(generating = false, error = "生成配对码失败：${detail.replace(apiKey, "***")}") }
            }
        }
    }

    private suspend fun <T> withPairingConnection(baseUrl: String, apiKey: String, block: suspend (DaemonApi) -> T): T =
        withContext(Dispatchers.IO) {
            val connection = ApiClient.buildDaemonConnection(baseUrl, apiKey)
            try {
                block(connection.api)
            } finally {
                connection.client.dispatcher.cancelAll()
                connection.client.dispatcher.executorService.shutdown()
                connection.client.connectionPool.evictAll()
            }
        }

    /** 本机已保存的同一 Daemon 的 API Key（优先当前正在编辑的那台），用于向服务端证明是同一台设备。 */
    private suspend fun findPreviousApiKey(url: String): String? {
        val settings = try { store.settingsFlow.first() } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        val matches = settings.daemons.filter {
            it.apiKey.isNotBlank() && samePairingEndpoint(it.baseUrl, url)
        }
        return (matches.firstOrNull { it.id == _state.value.editingId } ?: matches.firstOrNull())?.apiKey
    }

    /** 关闭配对二维码弹窗。 */
    fun dismissPairCode() = _state.update { it.copy(pairCode = null) }

}
