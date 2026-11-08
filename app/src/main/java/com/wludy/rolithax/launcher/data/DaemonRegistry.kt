package com.wludy.rolithax.launcher.data

import com.wludy.rolithax.launcher.data.remote.ApiClient
import com.wludy.rolithax.launcher.data.model.StatusData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 单个 Daemon 的连接状态。 */
enum class DaemonState { UNKNOWN, ONLINE, OFFLINE }

/** Daemon 选择项（新建实例页 / 用户中心等“先选一台服务端”的场景共用）。 */
data class DaemonOption(val id: String, val name: String)

/** 单个 Daemon 的运行时状态（并行探测结果）。 */
data class DaemonStatus(
    val id: String,
    val name: String = "",
    val state: DaemonState = DaemonState.UNKNOWN,
    val message: String = "",
    val version: String? = null,
    val instanceCount: Int? = null,
    val latencyMs: Long? = null,
    /** 当前健康探测选中的地址。 */
    val activeBaseUrl: String? = null,
    /** 备用地址探测结果，供设置页展示诊断。 */
    val endpointLatencies: Map<String, Long> = emptyMap(),
    /** 宿主系统信息（来自 /api/status，供主页 Daemon 卡片展示）。 */
    val osType: String? = null,
    val osArchitecture: String? = null,
    /**
     * 是否为「默认 Daemon」——仅用作新建实例的默认落点与主页首屏页码，
     * **不代表任何连接优先级**（各 Daemon 地位完全对等）。
     */
    val isDefault: Boolean = false,
    val checkedAt: Long = 0L,
) {
    val stateText: String
        get() = when (state) {
            DaemonState.ONLINE -> "在线"
            DaemonState.OFFLINE -> "离线"
            DaemonState.UNKNOWN -> "未检测"
        }
}

/**
 * **多 Daemon 同时连接管理（无主连接）**：每台 Daemon 各自持有一个 [InstanceRepository]
 * （独立的 REST 客户端与 SignalR 客户端工厂），按 daemonId 索引，状态探测并行执行、互不影响。
 *
 * 页面一律通过 [repositoryFor] 取得目标连接，再以 [ServerRef] 定位实例；
 * 不存在「当前全局连接」，因此任一页面的操作不会影响其它 Daemon 的上下文。
 */
class DaemonRegistry {

    /** daemonId → 该 Daemon 的独立连接。 */
    private val repositories = ConcurrentHashMap<String, InstanceRepository>()
    private val lastEndpoint = ConcurrentHashMap<String, String>()
    private val lastSwitchAt = ConcurrentHashMap<String, Long>()
    private var configured = emptyMap<String, DaemonConfig>()
    private var defaultId: String? = null
    private val probeVersions = mutableMapOf<String, Long>()
    private var nextProbeVersion = 0L

    private val _statuses = MutableStateFlow<Map<String, DaemonStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, DaemonStatus>> = _statuses.asStateFlow()

    /** 已建立连接的 Daemon 数量。 */
    val connectedCount: Int get() = repositories.size

    /**
     * 按设置同步连接：为每台已配置 Daemon 建立（或复用）连接并下发配置，
     * 设置里已删除的 Daemon 会移除其连接与状态。
     */
    @Synchronized
    fun sync(settings: AppSettings) {
        val next = settings.daemons.associateBy { it.id }
        val ids = next.keys
        val changed = (configured.keys + ids).filter { id ->
            val old = configured[id]
            val new = next[id]
            old?.copy(name = "") != new?.copy(name = "")
        }.toSet()
        configured = next
        defaultId = settings.activeDaemonId
        repositories.keys.toList().filter { it !in ids }.forEach { id ->
            repositories.remove(id)?.close()
        }
        changed.forEach {
            lastEndpoint.remove(it)
            lastSwitchAt.remove(it)
            probeVersions.remove(it)
        }

        val invalid = mutableSetOf<String>()
        settings.daemons.forEach { daemon ->
            // 配置变更判定已下沉到 InstanceRepository.configure（内部早退），此处直接调用
            val configuredEndpoints = (listOf(daemon.baseUrl) + daemon.endpoints)
                .map { ApiClient.normalizeDaemonUrl(it, daemon.allowHttp) }
                .filter { it.isNotBlank() }
            val endpoint = lastEndpoint[daemon.id]
                ?.takeIf { it in configuredEndpoints }
                ?: configuredEndpoints.firstOrNull().orEmpty()
            val repository = repositories.getOrPut(daemon.id) { InstanceRepository() }
            try {
                repository.configure(endpoint, daemon.apiKey, daemon.allowHttp)
            } catch (_: IllegalArgumentException) {
                repository.close()
                invalid += daemon.id
            }
        }

        _statuses.update { map ->
            next.mapValues { (id, daemon) ->
                val status = map[id]?.takeUnless { id in changed } ?: DaemonStatus(id)
                if (id in invalid) {
                    status.copy(name = daemon.name, isDefault = id == defaultId, state = DaemonState.OFFLINE, message = "Daemon 地址无效")
                } else {
                    status.copy(name = daemon.name, isDefault = id == defaultId)
                }
            }
        }
    }

    /** 取得某 Daemon 的连接（未同步或不存在时返回 null）。 */
    fun repositoryFor(daemonId: String): InstanceRepository? = repositories[daemonId]

    fun statusOf(daemonId: String): DaemonStatus =
        _statuses.value[daemonId] ?: DaemonStatus(daemonId)

    /** 全部已配置 Daemon 的连接（供多路订阅/聚合场景使用）。 */
    fun allRepositories(): Map<String, InstanceRepository> = repositories.toMap()

    @Synchronized
    fun close() {
        repositories.values.forEach { it.close() }
        repositories.clear()
        lastEndpoint.clear()
        lastSwitchAt.clear()
        configured = emptyMap()
        probeVersions.clear()
        _statuses.value = emptyMap()
    }

    @Synchronized
    fun recordInstanceCount(config: DaemonConfig, count: Int?) {
        if (configured[config.id]?.copy(name = "") != config.copy(name = "")) return
        _statuses.update { map -> map + (config.id to statusOf(config.id).copy(instanceCount = count)) }
    }

    internal suspend fun verifyConnection(config: DaemonConfig): EndpointProbe {
        var failure: Throwable? = null
        for (endpoint in endpoints(config)) {
            val probe = withContext(Dispatchers.IO) { probeEndpoint(config, endpoint, verify = true) }
            if (probe.result.isSuccess) return probe
            if (failure == null) failure = probe.result.exceptionOrNull()
        }
        throw failure ?: IllegalArgumentException("Daemon 地址无效")
    }

    @Synchronized
    internal fun syncVerified(settings: AppSettings, config: DaemonConfig, probe: EndpointProbe) {
        sync(settings)
        if (configured[config.id] != config) return
        val version = ++nextProbeVersion
        probeVersions[config.id] = version
        applyProbe(config, version, listOf(probe))
    }

    /** 刷新单个 Daemon 状态。 */
    suspend fun refreshOne(config: DaemonConfig) {
        val version = synchronized(this) {
            if (configured[config.id]?.copy(name = "") != config.copy(name = "")) return
            (++nextProbeVersion).also { probeVersions[config.id] = it }
        }
        val results = coroutineScope {
            endpoints(config).map { endpoint ->
                async(Dispatchers.IO) { probeEndpoint(config, endpoint) }
            }.awaitAll()
        }
        applyProbe(config, version, results)
    }

    private fun endpoints(config: DaemonConfig): List<String> =
        (listOf(config.baseUrl) + config.endpoints)
            .map { ApiClient.normalizeDaemonUrl(it, config.allowHttp) }
            .filter { it.isNotBlank() }
            .distinct()

    private suspend fun probeEndpoint(config: DaemonConfig, endpoint: String, verify: Boolean = false): EndpointProbe {
        val probe = InstanceRepository()
        val started = System.currentTimeMillis()
        try {
            probe.configure(endpoint, config.apiKey, config.allowHttp)
            val result = if (verify) Result.success(probe.verify()) else probe.getStatus()
            return EndpointProbe(endpoint, System.currentTimeMillis() - started, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = if (e is IllegalArgumentException) IllegalArgumentException("Daemon 地址无效") else e
            return EndpointProbe(endpoint, System.currentTimeMillis() - started, Result.failure(failure))
        } finally {
            probe.close()
        }
    }

    @Synchronized
    private fun applyProbe(config: DaemonConfig, version: Long, results: List<EndpointProbe>) {
        if (probeVersions[config.id] != version) return
        val latest = configured[config.id] ?: return
        val repository = repositoryFor(config.id) ?: return
        val successful = results.filter { it.result.isSuccess }.sortedBy { it.latencyMs }
        val fastest = successful.firstOrNull()
        val previousEndpoint = lastEndpoint[config.id]
        val previous = previousEndpoint?.let { endpoint -> successful.firstOrNull { it.endpoint == endpoint } }
        val now = System.currentTimeMillis()
        val selected = when {
            previous == null -> fastest
            fastest == null -> previous
            previous.latencyMs <= (fastest.latencyMs * 1.15).toLong().coerceAtLeast(fastest.latencyMs + 1) -> previous
            now - (lastSwitchAt[config.id] ?: 0L) < ENDPOINT_SWITCH_COOLDOWN_MS -> previous
            else -> fastest
        }
        val latency = selected?.latencyMs ?: results.minOfOrNull { it.latencyMs } ?: 0L
        val checkedAt = System.currentTimeMillis()

        if (selected == null) {
            val failure = results.firstOrNull()?.result?.exceptionOrNull()
            _statuses.update { map ->
                map + (config.id to DaemonStatus(
                    id = config.id,
                    name = latest.name,
                    state = DaemonState.OFFLINE,
                    message = failure?.message ?: "连接失败",
                    latencyMs = latency,
                    endpointLatencies = results.associate { it.endpoint to it.latencyMs },
                    isDefault = config.id == defaultId,
                    checkedAt = checkedAt,
                ))
            }
            return
        }

        repository.configure(selected.endpoint, config.apiKey, config.allowHttp)
        if (previousEndpoint != selected.endpoint) {
            lastEndpoint[config.id] = selected.endpoint
            lastSwitchAt[config.id] = now
        }
        val status = selected.result.getOrThrow()
        _statuses.update { map ->
            map + (config.id to DaemonStatus(
                id = config.id,
                name = latest.name,
                state = DaemonState.ONLINE,
                version = status.version,
                instanceCount = map[config.id]?.instanceCount,
                latencyMs = latency,
                activeBaseUrl = selected.endpoint,
                endpointLatencies = results.associate { it.endpoint to it.latencyMs },
                osType = status.systemInfo?.osType,
                osArchitecture = status.systemInfo?.osArchitecture,
                isDefault = config.id == defaultId,
                checkedAt = checkedAt,
            ))
        }
        AppLogger.i("DaemonRegistry", "Daemon 在线：${latest.name.ifBlank { config.id }} v${status.version ?: "?"} ${latency}ms")
    }

    internal data class EndpointProbe(
        val endpoint: String,
        val latencyMs: Long,
        val result: Result<StatusData>,
    )

    private companion object {
        const val ENDPOINT_SWITCH_COOLDOWN_MS = 60_000L
    }

}
