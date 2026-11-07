package com.wludy.rolithax.launcher.ui.console

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wludy.rolithax.launcher.RolithaxApplication
import com.wludy.rolithax.launcher.data.localengine.EngineKind
import com.wludy.rolithax.launcher.data.localengine.LocalInstanceStore
import com.wludy.rolithax.launcher.data.localengine.LocalServerRuntime
import com.wludy.rolithax.launcher.shared.console.ConsolePluginSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 本机实例的统一控制台控制器：把进程级 [LocalServerRuntime] 的运行状态/日志映射为
 * [ConsoleUiState]，启停经 [com.wludy.rolithax.launcher.data.localengine.LocalServerManager]，
 * 从而让 [ConsoleScreen] 对本机与 Daemon 实例使用同一套界面。
 *
 * 状态码沿用全局约定：0 未启动 / 1 启动中 / 2 运行中 / 4 重启中。本机无网络语义，
 * 始终视为「已连接」（connected=true, connecting=false）。
 */
class LocalConsoleViewModel(
    application: Application,
    private val dirName: String,
) : AndroidViewModel(application), ConsoleController {

    private val manager = getApplication<RolithaxApplication>().container.localServerManager
    private val panelState = ConsolePanelState()
    override val panel = panelState.state
    private val ansiParser = AnsiStreamParser()
    private val rendered = linkedMapOf<Long, LogLine>()
    private var observedGeneration = -1L
    private var lastLineId: Long? = null

    private val _state = MutableStateFlow(
        ConsoleUiState(
            instanceName = LocalInstanceStore.load(application, dirName)?.name?.takeIf { it.isNotBlank() } ?: dirName,
            connecting = false,
            connected = true,
        ),
    )
    override val state: StateFlow<ConsoleUiState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    override val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _events = MutableSharedFlow<ConsoleEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<ConsoleEvent> = _events.asSharedFlow()

    init {
        // 运行状态 → 状态码（仅当运行中的正是本实例）
        viewModelScope.launch {
            LocalServerRuntime.running.collect {
                val mine = manager.isRunning(dirName)
                val status = when {
                    mine -> 2
                    _state.value.busy -> _state.value.status // 保留过渡态
                    else -> 0
                }
                _state.update {
                    it.copy(
                        status = status,
                        statusText = if (mine) "运行中" else "未启动",
                    )
                }
            }
        }
        viewModelScope.launch {
            LocalServerRuntime.consoleOutput.collect { output ->
                if (observedGeneration != output.generation) {
                    observedGeneration = output.generation
                    lastLineId = null
                    rendered.clear()
                    ansiParser.reset()
                    panelState.reset()
                }
                if (output.directory == dirName) {
                    val previous = lastLineId
                    val first = output.lines.firstOrNull()?.id
                    if (first != null && first > (previous ?: 0L) + 1L) {
                        rendered[first - 1] = LogLine("部分输出已超出缓冲范围", system = true)
                        panelState.markGap()
                        ansiParser.reset()
                    }
                    if (first != null) rendered.keys.removeAll { it < first - 1 }
                    for (line in output.lines) {
                        if (previous != null && line.id <= previous) continue
                        if (!line.system) panelState.onLine(line.text)
                        val segments = if (line.system) null else ansiParser.append(line.text).ifEmpty { listOf(AnsiSegment("")) }
                        rendered[line.id] = LogLine(line.text, system = line.system, segments = segments)
                        lastLineId = line.id
                    }
                }
                _logs.value = rendered.values.toList()
                panelState.publish()
            }
        }
    }

    override suspend fun sendCommand(command: String): Boolean {
        val cmd = command.trim()
        if (cmd.isEmpty()) return false
        if (cmd.length > MAX_CONSOLE_COMMAND_CHARS || cmd.any(Char::isISOControl)) {
            _events.tryEmit(ConsoleEvent.Toast("请输入不超过 1024 字符的单行命令"))
            return false
        }
        if (!_state.value.canSendCommand || !LocalServerRuntime.sendCommand(dirName, cmd)) {
            _events.tryEmit(ConsoleEvent.Toast("无法写入本机控制台，输入已保留"))
            return false
        }
        return true
    }

    override fun previewPanelAction(actionId: String, arguments: Map<String, String>, packageRevision: Long): String? =
        panelState.command(actionId, arguments, packageRevision)

    override suspend fun sendPanelAction(actionId: String, arguments: Map<String, String>, packageRevision: Long): Boolean {
        val command = previewPanelAction(actionId, arguments, packageRevision)
        if (command == null) {
            _events.tryEmit(ConsoleEvent.Toast("该操作未获授权或参数无效"))
            return false
        }
        return sendCommand(command)
    }

    override fun loadPanel(source: ConsolePluginSource?, allowedCommands: Set<String>) {
        try {
            panelState.load(source, allowedCommands)
        } catch (_: IllegalArgumentException) {
            _events.tryEmit(ConsoleEvent.Toast("扩展加载失败，请检查包格式"))
        }
    }

    override fun sendAction(action: String) {
        if (_state.value.busy) return
        when (action) {
            "start" -> doStart()
            "stop" -> {
                if (!LocalServerRuntime.stop(dirName)) {
                    _events.tryEmit(ConsoleEvent.Toast("服务端未运行"))
                    return
                }
                _state.update { it.copy(status = 3, statusText = "停止中") }
                _events.tryEmit(ConsoleEvent.Toast("已发送 stop，等待服务端保存并退出"))
            }
            "restart" -> doRestart()
            "forceExit" -> {
                if (!LocalServerRuntime.stop(dirName)) {
                    _events.tryEmit(ConsoleEvent.Toast("服务端未运行"))
                    return
                }
                _state.update { it.copy(status = 3, statusText = "停止中") }
                _events.tryEmit(ConsoleEvent.Toast("已请求结束服务端"))
            }
            "backup" -> _events.tryEmit(ConsoleEvent.Toast("本机实例备份暂未提供，请通过文件管理导出世界目录"))
            else -> _events.tryEmit(ConsoleEvent.Toast("该操作在本机实例暂不支持"))
        }
    }

    private fun doStart() {
        _state.update { it.copy(busy = true, status = 1, statusText = "启动中") }
        viewModelScope.launch {
            manager.start(dirName)
                .onSuccess {
                    _state.update { it.copy(busy = false) }
                    _events.tryEmit(ConsoleEvent.Toast("启动成功"))
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, status = 0, statusText = "未启动") }
                    _events.tryEmit(ConsoleEvent.Toast("启动失败：${e.message ?: "详见日志"}"))
                }
        }
    }

    private fun doRestart() {
        _state.update { it.copy(busy = true, status = 4, statusText = "重启中") }
        viewModelScope.launch {
            manager.restart(dirName)
                .onSuccess {
                    _state.update { it.copy(busy = false) }
                    _events.tryEmit(ConsoleEvent.Toast("重启完成"))
                }
                .onFailure { e ->
                    val running = manager.isRunning(dirName)
                    _state.update {
                        it.copy(
                            busy = false,
                            status = if (running) 2 else 0,
                            statusText = if (running) "运行中" else "未启动",
                        )
                    }
                    _events.tryEmit(ConsoleEvent.Toast(e.message ?: "重启失败"))
                }
        }
    }

    override fun retryConnect() {
        // 本机控制台无连接语义：刷新一次状态即可
        _state.update { it.copy(connecting = false, connected = true, connectionError = null) }
    }

    override fun agreeEulaAndStart() {
        // 本机实例创建/启动流程已自动同意 EULA（见 ServerFiles.complete），直接启动
        doStart()
    }

    override fun clearLogs() {
        rendered.clear()
        _logs.value = emptyList()
    }

    /** 引擎类型提示：进程内基线在停止后需重启 App，供上层按需提示。 */
    val activeKind: EngineKind? get() = manager.activeKind
}
