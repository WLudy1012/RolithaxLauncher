package com.wludy.rolithax.launcher.ui.panels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wludy.rolithax.launcher.RolithaxApplication
import com.wludy.rolithax.launcher.shared.panels.PanelPower
import com.wludy.rolithax.launcher.shared.panels.PanelProfile
import com.wludy.rolithax.launcher.shared.panels.PanelRequestException
import com.wludy.rolithax.launcher.shared.panels.PanelServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class PanelUiState(
    val profiles: List<PanelProfile> = emptyList(),
    val ready: Boolean = false,
    val storageError: Boolean = false,
    val profileId: String? = null,
    val servers: List<PanelServer> = emptyList(),
    val server: PanelServer? = null,
    val powers: Set<PanelPower> = emptySet(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val logText: String = "",
    val logError: String? = null,
    val logConnecting: Boolean = false,
    val saveVersion: Int = 0,
)

class PanelViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = getApplication<RolithaxApplication>().container.panelRepository
    private val mutableState = MutableStateFlow(PanelUiState())
    val state = mutableState.asStateFlow()
    private var operationJob: Job? = null
    private var logJob: Job? = null
    private var generation = 0
    private var logGeneration = 0
    private var visible = false

    init {
        viewModelScope.launch {
            repository.store.profiles.catch {
                closeProfile()
                mutableState.update {
                    it.copy(profiles = emptyList(), ready = true, storageError = true,
                        error = "无法读取保存的面板连接，请检查设备密钥和存储。原配置已保留。")
                }
            }.collect { profiles ->
                val previous = selectedProfile()
                if (previous != null && previous !in profiles) {
                    stopLogs()
                    mutableState.update {
                        it.copy(profileId = null, servers = emptyList(), server = null, powers = emptySet(),
                            logText = "", logError = null, error = null, message = null)
                    }
                }
                mutableState.update { it.copy(profiles = profiles, ready = true, storageError = false) }
            }
        }
    }

    fun clearNotice() = mutableState.update { it.copy(error = null, message = null) }

    fun consumeMessage(message: String) = mutableState.update {
        if (it.message == message) it.copy(message = null) else it
    }

    fun save(input: PanelProfile) {
        if (state.value.busy || !state.value.ready || state.value.storageError) return
        val profile = input.copy(name = input.name.trim().ifBlank { input.kind.title },
            baseUrl = input.baseUrl.trim(), token = input.token.trim(), nodeId = input.nodeId.trim())
        if (profile.id.isBlank() || profile.baseUrl.isBlank() || profile.token.isBlank()) {
            mutableState.update { it.copy(error = "请填写面板地址和访问密钥。") }
            return
        }
        runOperation {
            val (servers, powers) = repository.withClient(profile) { it.servers() to it.powers }
            repository.store.save(profile)
            currentCoroutineContext().ensureActive()
            stopLogs()
            mutableState.update { it.copy(
                profiles = it.profiles.filterNot { saved -> saved.id == profile.id } + profile,
                profileId = profile.id, servers = servers, server = null, powers = powers,
                logText = "", logError = null, message = "已验证并保存连接", saveVersion = it.saveVersion + 1,
            ) }
        }
    }

    fun remove(id: String) {
        if (state.value.busy || state.value.storageError || state.value.profiles.none { it.id == id }) return
        runOperation {
            repository.store.remove(id)
            currentCoroutineContext().ensureActive()
            if (state.value.profileId == id) {
                stopLogs()
                mutableState.update { it.copy(profileId = null, server = null, servers = emptyList(),
                    powers = emptySet(), logText = "", logError = null) }
            }
            mutableState.update { it.copy(profiles = it.profiles.filterNot { profile -> profile.id == id }, message = "已移除连接") }
        }
    }

    fun selectProfile(id: String) {
        if (state.value.busy || state.value.storageError || state.value.profiles.none { it.id == id }) return
        stopLogs()
        mutableState.update { it.copy(profileId = id, servers = emptyList(), server = null,
            powers = emptySet(), logText = "", logError = null, error = null, message = null) }
        refresh()
    }

    fun closeProfile() {
        generation++
        operationJob?.cancel()
        stopLogs()
        mutableState.update { it.copy(profileId = null, servers = emptyList(), server = null,
            busy = false, error = null, message = null, powers = emptySet(), logText = "", logError = null) }
    }

    fun refresh() {
        if (state.value.busy) return
        val profile = selectedProfile() ?: return
        runOperation(ownsResult = { selectedProfile() == profile }) {
            val (servers, powers) = repository.withClient(profile) { it.servers() to it.powers }
            currentCoroutineContext().ensureActive()
            if (selectedProfile() != profile) return@runOperation
            val selected = state.value.server?.let { old -> servers.find { it.sameInstance(old) } }
            if (selected == null) stopLogs()
            mutableState.update { it.copy(servers = servers, powers = powers, server = selected) }
        }
    }

    fun selectServer(server: PanelServer?) {
        val current = state.value
        if (server != null && (current.busy || selectedProfile() == null)) return
        val selected = server?.let { target -> current.servers.find { it.sameInstance(target) } ?: return }
        stopLogs()
        mutableState.update { it.copy(server = selected, logText = "", logError = null, error = null, message = null) }
        if (selected != null) reconnectLogs()
    }

    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        if (isVisible) reconnectLogs() else stopLogs()
    }

    fun reconnectLogs() {
        if (!visible || logJob?.isActive == true) return
        val profile = selectedProfile() ?: return
        val server = state.value.server ?: return
        val stream = ++logGeneration
        fun ownsLogs() = visible && stream == logGeneration && isSelected(profile, server)
        mutableState.update { it.copy(logConnecting = true, logError = null) }
        logJob = viewModelScope.launch {
            try {
                repository.withClient(profile) { client ->
                    client.logs(server) { logs ->
                        val snapshot = logs.takeLast(65_536)
                        withContext(Dispatchers.Main.immediate) {
                            if (ownsLogs()) mutableState.update { it.copy(logText = snapshot, logConnecting = false) }
                        }
                    }
                }
                if (ownsLogs()) {
                    mutableState.update { it.copy(logError = "日志连接已结束。", logConnecting = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (ownsLogs()) {
                    mutableState.update { it.copy(logError = "日志连接中断，请检查实例状态、控制台权限与网络后重试。", logConnecting = false) }
                }
            }
        }
    }

    fun power(action: PanelPower) {
        val current = state.value
        if (current.busy || action !in current.powers) return
        val profile = selectedProfile() ?: return
        val server = current.server ?: return
        runOperation(ownsResult = { isSelected(profile, server) }) {
            repository.withClient(profile) { it.power(server, action) }
            currentCoroutineContext().ensureActive()
            if (isSelected(profile, server)) mutableState.update { it.copy(message = "已发送${action.title}请求，可刷新查看状态。") }
        }
    }

    fun command(text: String) {
        if (state.value.busy) return
        val command = text.trim()
        if (command.isEmpty() || '\n' in command || '\r' in command || command.length > 8192) {
            mutableState.update { it.copy(error = "请输入一行命令，长度不超过 8192 个字符。") }
            return
        }
        val profile = selectedProfile() ?: return
        val server = state.value.server ?: return
        runOperation(ownsResult = { isSelected(profile, server) }) {
            repository.withClient(profile) { it.command(server, command) }
            currentCoroutineContext().ensureActive()
            if (isSelected(profile, server)) mutableState.update { it.copy(message = "命令已发送") }
        }
    }

    private fun selectedProfile(): PanelProfile? = state.value.let { current ->
        current.profiles.find { it.id == current.profileId }
    }

    private fun isSelected(profile: PanelProfile, server: PanelServer): Boolean =
        selectedProfile() == profile && state.value.server?.sameInstance(server) == true

    private fun PanelServer.sameInstance(other: PanelServer) = id == other.id && nodeId == other.nodeId

    private fun stopLogs() {
        logGeneration++
        logJob?.cancel()
        logJob = null
        mutableState.update { it.copy(logConnecting = false) }
    }

    private fun runOperation(ownsResult: () -> Boolean = { true }, block: suspend () -> Unit) {
        operationJob?.cancel()
        val operation = ++generation
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        operationJob = viewModelScope.launch {
            try {
                withTimeout(45_000) { block() }
            } catch (_: TimeoutCancellationException) {
                if (generation == operation && ownsResult()) {
                    mutableState.update { it.copy(error = "请求超时，请检查面板地址和网络后重试。") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == operation && ownsResult()) {
                    val message = (e as? PanelRequestException)?.message
                        ?: "连接或操作失败。请检查面板类型、地址、密钥与实例权限；使用 HTTP / WS 时需明确允许明文连接。"
                    mutableState.update { it.copy(error = message) }
                }
            } finally {
                if (generation == operation) mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
