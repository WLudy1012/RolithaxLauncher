package com.wludy.rolithax.launcher.ui.instances

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wludy.rolithax.launcher.RolithaxApplication
import com.wludy.rolithax.launcher.data.AppLogger
import com.wludy.rolithax.launcher.data.DaemonOption
import com.wludy.rolithax.launcher.data.InstanceIcons
import com.wludy.rolithax.launcher.data.InstancePinStore
import com.wludy.rolithax.launcher.data.ManagedServer
import com.wludy.rolithax.launcher.data.ensureRepository
import com.wludy.rolithax.launcher.data.isStoppableStatus
import com.wludy.rolithax.launcher.data.localengine.LocalInstanceStore
import com.wludy.rolithax.launcher.data.localengine.LocalServerRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class InstancesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: String? = null,
    val error: String? = null,
    val servers: List<ManagedServer> = emptyList(),
    val daemonOptions: List<DaemonOption> = emptyList(),
    val daemonFailures: Map<String, String> = emptyMap(),
    /** 实例图标（key = [ManagedServer.key]）；未加载到的不显示（占位状态块）。 */
    val icons: Map<String, Bitmap> = emptyMap(),
    /** 正在下发启停操作的实例 key（对应行按钮显示进度，避免重复下发）。 */
    val busyKeys: Set<String> = emptySet(),
    /** 一键停止全部进行中。 */
    val stoppingAll: Boolean = false,
    /** 操作结果提示（由 UI 弹 Snackbar 后调 [InstancesViewModel.clearActionMessage] 清空）。 */
    val actionMessage: String? = null,
    val pinnedKeys: Set<String> = emptySet(),
)

/**
 * 统一实例列表：本机开服实例 + **所有** Daemon 的实例（经 [com.wludy.rolithax.launcher.data.ServerCatalog] 聚合）。
 * 条目一律用 [com.wludy.rolithax.launcher.data.ServerRef] 定位：删哪个服务端的实例就操作哪台的仓储。
 */
class InstancesViewModel(application: Application) : AndroidViewModel(application) {

    private val container = getApplication<RolithaxApplication>().container
    private val catalog = container.serverCatalog
    private val store = container.settingsStore
    private val pins = InstancePinStore(application)

    private val _state = MutableStateFlow(InstancesUiState())
    val state = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        _state.update { it.copy(pinnedKeys = pins.load()) }
    }

    fun togglePin(server: ManagedServer) {
        val pinned = server.key !in _state.value.pinnedKeys
        pins.setPinned(server.key, pinned)
        _state.update { current ->
            val pinnedKeys = current.pinnedKeys.toMutableSet().apply {
                if (pinned) add(server.key) else remove(server.key)
            }
            current.copy(
                pinnedKeys = pinnedKeys,
                servers = current.servers.sortedWith(
                    compareByDescending<ManagedServer> { it.key in pinnedKeys }.thenBy { it.name.lowercase() },
                ),
            )
        }
    }

    fun delete(server: ManagedServer, deleteFiles: Boolean, onDone: () -> Unit) {
        if (_state.value.deleting) return
        _state.update { it.copy(deleting = true, deleteError = null) }
        viewModelScope.launch {
            if (server.isLocal) {
                val dir = server.localDirName
                if (dir.isNullOrBlank()) {
                    _state.update { it.copy(deleting = false, deleteError = "无法确定实例目录") }
                    return@launch
                }
                if (LocalServerRuntime.isRunningOrStarting(dir)) {
                    _state.update { it.copy(deleting = false, deleteError = "本地实例正在启动或运行，请停止后再删除") }
                    return@launch
                }
                LocalInstanceStore.delete(getApplication(), dir).fold(
                    onSuccess = {
                        _state.update { it.copy(deleting = false, deleteError = null) }
                        onDone()
                        refresh()
                    },
                    onFailure = { e ->
                        _state.update { it.copy(deleting = false, deleteError = e.message ?: "删除失败") }
                    },
                )
            } else {
                val ref = server.ref
                val id = server.remoteId
                if (ref == null || id == null) {
                    _state.update { it.copy(deleting = false, deleteError = "无法确定实例归属的服务端") }
                    return@launch
                }
                val repository = container.ensureRepository(ref.daemonId)
                if (repository == null) {
                    _state.update { it.copy(deleting = false, deleteError = "该实例所属服务端已不存在，请先检查连接配置") }
                    return@launch
                }
                repository.deleteInstance(id, deleteFiles).fold(
                    onSuccess = {
                        _state.update { it.copy(deleting = false, deleteError = null) }
                        onDone()
                        refresh()
                    },
                    onFailure = { e ->
                        _state.update { it.copy(deleting = false, deleteError = e.message ?: "删除失败") }
                    },
                )
            }
        }
    }

    fun clearActionMessage() {
        _state.update { it.copy(actionMessage = null) }
    }

    /**
     * 启停单个实例（按 [ManagedServer.ref] 下发：本机实例走 LocalServerManager，
     * 远程实例走它所属那台 Daemon 的仓储，去主连接后不再存在“当前连接”）。
     */
    fun toggle(server: ManagedServer, start: Boolean) {
        if (server.key in _state.value.busyKeys) return
        _state.update { it.copy(busyKeys = it.busyKeys + server.key, actionMessage = null) }
        viewModelScope.launch {
            val result = runCatchingWithBusy(server, start)
            _state.update { it.copy(busyKeys = it.busyKeys - server.key) }
            result.fold(
                onSuccess = { message ->
                    _state.update { it.copy(actionMessage = message) }
                    refresh()
                },
                onFailure = { e ->
                    AppLogger.w("Instances", "实例操作失败：${server.name}", e)
                    _state.update { it.copy(actionMessage = e.message ?: "操作失败") }
                },
            )
        }
    }

    /**
     * 一键停止全部：逐个按实例归属下发 stop（过渡态也算需停），失败项汇总提示。
     * 串行而非并行：避开部分 Daemon 对同一实例并发操作的拒绝，也让失败原因能逐项归属。
     */
    fun stopAll(servers: List<ManagedServer> = _state.value.servers) {
        if (_state.value.stoppingAll) return
        val selected = servers.mapTo(mutableSetOf(), ManagedServer::key)
        val targets = _state.value.servers.filter {
            it.key in selected && it.key !in _state.value.busyKeys && isStoppableStatus(it.status)
        }
        if (targets.isEmpty()) {
            _state.update { it.copy(actionMessage = "没有运行中的实例") }
            return
        }
        val keys = targets.mapTo(mutableSetOf(), ManagedServer::key)
        _state.update { it.copy(stoppingAll = true, busyKeys = it.busyKeys + keys, actionMessage = null) }
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            try {
                targets.forEach { server ->
                    runCatchingWithBusy(server, start = false)
                        .onFailure { failed += "${server.name}（${server.sourceLabel}）" }
                }
            } finally {
                _state.update { it.copy(stoppingAll = false, busyKeys = it.busyKeys - keys) }
            }
            _state.update {
                it.copy(
                    actionMessage = when {
                        failed.isEmpty() -> "已向 ${targets.size} 个实例发送停止命令"
                        failed.size == targets.size -> "全部停止失败：${failed.joinToString("、")}"
                        else -> "已发送 ${targets.size - failed.size} 个停止命令，失败：${failed.joinToString("、")}"
                    },
                )
            }
            refresh()
        }
    }

    /** 下发一次启停，返回可直接展示的结果文案；失败以异常抛出。 */
    private suspend fun runCatchingWithBusy(server: ManagedServer, start: Boolean): Result<String> {
        val message = if (server.isLocal) {
            val dir = server.localDirName
            if (dir.isNullOrBlank()) return Result.failure(IllegalStateException("无法确定实例目录"))
            if (!start) {
                if (!LocalServerRuntime.stop(dir)) {
                    return Result.failure(IllegalStateException("该本机实例已停止或尚未启动"))
                }
                "已发送停止命令：${server.name}"
            } else {
                container.localServerManager.start(dir)
                    .map { "已启动本机实例：${server.name}" }
                    .getOrElse { e -> return Result.failure(e) }
            }
        } else {
            val ref = server.ref
            val id = server.remoteId
            if (ref == null || id == null) return Result.failure(IllegalStateException("无法确定实例归属的服务端"))
            val repository = container.ensureRepository(ref.daemonId)
                ?: return Result.failure(IllegalStateException("该实例所属服务端已不存在，请先检查连接配置"))
            repository.sendAction(id, if (start) "start" else "stop")
                .map { "已发送${if (start) "启动" else "停止"}命令：${server.name}（${server.sourceLabel}）" }
                .getOrElse { e -> return Result.failure(e) }
        }
        AppLogger.i("Instances", message)
        return Result.success(message)
    }

    fun refresh(initial: Boolean = false) {
        refreshJob?.cancel()
        if (initial) {
            _state.update { it.copy(loading = true, error = null) }
        } else {
            _state.update { it.copy(refreshing = true, error = null) }
        }
        refreshJob = viewModelScope.launch {
            try {
                val settings = store.settingsFlow.first()
                val snapshot = catalog.load(settings, refreshDaemonStatuses = !initial)
                val servers = snapshot.servers
                val pinned = pins.load()
                val ordered = servers.sortedWith(compareByDescending<ManagedServer> { it.key in pinned }.thenBy { it.name.lowercase() })
                _state.update {
                    val keys = ordered.mapTo(mutableSetOf(), ManagedServer::key)
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = null,
                        servers = ordered,
                        daemonOptions = settings.daemons.map { DaemonOption(it.id, it.name.ifBlank { it.baseUrl }) },
                        daemonFailures = snapshot.daemonFailures,
                        pinnedKeys = pinned,
                        icons = it.icons.filterKeys(keys::contains),
                    )
                }
                val icons = InstanceIcons.load(container, servers)
                _state.update { current ->
                    val keys = current.servers.mapTo(mutableSetOf(), ManagedServer::key)
                    current.copy(icons = current.icons + icons.filterKeys(keys::contains))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("Instances", "加载统一实例列表失败", e)
                _state.update {
                    it.copy(loading = false, refreshing = false, error = e.message ?: "加载失败")
                }
            }
        }
    }
}
