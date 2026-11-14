package com.wludy.rolithax.launcher.ui.console

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wludy.rolithax.launcher.RolithaxApplication
import com.wludy.rolithax.launcher.data.InstanceRepository
import com.wludy.rolithax.launcher.data.DaemonConnectionClosedException
import com.wludy.rolithax.launcher.data.ServerRef
import com.wludy.rolithax.launcher.data.SharedDaemonConnection
import com.wludy.rolithax.launcher.data.ensureRepository
import com.wludy.rolithax.launcher.shared.ConsoleCursor
import com.wludy.rolithax.launcher.shared.ConsoleEvent as DaemonConsoleEvent
import com.wludy.rolithax.launcher.shared.DaemonConsole
import com.wludy.rolithax.launcher.shared.console.ConsolePluginSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong

private const val EULA_AGREED_CONTENT =
    "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n#Rolithax Launcher auto agreed\neula=true\n"

private const val MAX_LOG_LINES = 3000
private const val MAX_LOG_CHARS = 1_000_000
private const val MAX_CONSOLE_LINE_CHARS = 8192
internal const val MAX_CONSOLE_COMMAND_CHARS = 1024

data class LogLine(
    val text: String,
    val system: Boolean = false,
    val segments: List<AnsiSegment>? = null,
    val id: Long = idGenerator.incrementAndGet(),
) {
    companion object {
        private val idGenerator = AtomicLong()
    }
}

sealed interface ConsoleEvent {
    data class Toast(val message: String) : ConsoleEvent
    data object EulaRequired : ConsoleEvent
}

data class ConsoleUiState(
    val instanceName: String = "",
    val status: Int = 0,
    val statusText: String? = null,
    val onlinePlayers: Int = 0,
    val uptime: String? = null,
    val connecting: Boolean = true,
    val connected: Boolean = false,
    val connectionError: String? = null,
    val busy: Boolean = false,
) {
    val canSendCommand: Boolean get() = connected && status == 2 && !busy
}

class ConsoleViewModel(
    application: Application,
    private val ref: ServerRef,
) : AndroidViewModel(application), ConsoleController {

    private val container = getApplication<RolithaxApplication>().container
    private val instanceId = ref.remoteIdOrNull?.takeIf { it in 0L..UInt.MAX_VALUE.toLong() }
    private val panelState = ConsolePanelState()
    override val panel = panelState.state

    private val _state = MutableStateFlow(ConsoleUiState(instanceName = "实例 #${ref.instanceId}"))
    override val state = _state.asStateFlow()
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    override val logs = _logs.asStateFlow()
    private val _events = MutableSharedFlow<ConsoleEvent>(extraBufferCapacity = 16)
    override val events = _events.asSharedFlow()

    private val ansiParser = AnsiStreamParser()
    private val renderBuffer = ArrayDeque<LogLine>()
    private var bufferChars = 0
    private var publishJob: Job? = null
    private var connectionJob: Job? = null
    private var session: DaemonConsole? = null
    private var connection: SharedDaemonConnection? = null
    private var resumeCursor: ConsoleCursor? = null
    private var streamId: String? = null
    private var linesUntilYield = 64

    init {
        retryConnect()
        viewModelScope.launch {
            val id = instanceId ?: return@launch
            while (isActive) {
                container.ensureRepository(ref.daemonId)?.let { loadInfo(it, id) }
                delay(15_000)
            }
        }
    }

    private suspend fun loadInfo(repository: InstanceRepository, id: Long) {
        repository.instanceInfo(id).onSuccess { info ->
            _state.update {
                it.copy(
                    instanceName = info.name ?: it.instanceName,
                    status = info.status,
                    statusText = info.statusText,
                    onlinePlayers = info.onlinePlayers,
                    uptime = info.uptime?.let(::formatUptime),
                )
            }
        }
    }

    private fun formatUptime(raw: String): String {
        val trimmed = raw.trim()
        val separator = trimmed.lastIndexOf('.')
        return if (separator > trimmed.lastIndexOf(':')) trimmed.substringBeforeLast('.') else trimmed
    }

    override fun retryConnect() {
        if (_state.value.connected) return
        val previous = connectionJob
        connectionJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            val id = instanceId
            if (id == null) {
                _state.update { it.copy(connecting = false, connectionError = "实例 ID 无效") }
                return@launch
            }
            while (isActive) {
                val source = container.ensureRepository(ref.daemonId)?.consoleConnection
                if (source == null) {
                    _state.update {
                        it.copy(connecting = false, connected = false, connectionError = "Daemon 连接不可用")
                    }
                    break
                }
                if (connection != null && connection !== source) {
                    ansiParser.reset()
                    resumeCursor = null
                    streamId = null
                    panelState.reset()
                    panelState.load(null, emptySet())
                    clearLogs()
                    append(LogLine("Daemon 配置已变更，已切换控制台会话", system = true))
                }
                connection = source
                _state.update { it.copy(connecting = true, connected = false, connectionError = null) }
                var terminalFailure = false
                try {
                    source.runConsole(id, resumeCursor, onSession = { session = it }, onEvent = { event ->
                        if (event is DaemonConsoleEvent.AuthenticationFailed || event == DaemonConsoleEvent.OutputTooLarge) {
                            terminalFailure = true
                        }
                        onConsoleEvent(event)
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _state.update {
                        it.copy(connecting = false, connected = false, connectionError = "控制台连接不可用")
                    }
                    break
                } finally {
                    session = null
                    _state.update { it.copy(connecting = false, connected = false) }
                }
                if (terminalFailure) break
                delay(250)
            }
        }
    }

    private suspend fun onConsoleEvent(event: DaemonConsoleEvent) {
        when (event) {
            DaemonConsoleEvent.Connected -> _state.update {
                it.copy(connecting = false, connected = true, connectionError = null)
            }
            DaemonConsoleEvent.Disconnected -> {
                _state.update { it.copy(connecting = false, connected = false, connectionError = null) }
                ansiParser.reset()
            }
            is DaemonConsoleEvent.AuthenticationFailed -> {
                val message = if (event.httpStatus == 403) {
                    "没有控制台访问权限，请检查实例授权"
                } else {
                    "鉴权失败，请检查 API Key"
                }
                _state.update {
                    it.copy(connecting = false, connected = false, connectionError = message)
                }
                ansiParser.reset()
            }
            DaemonConsoleEvent.OutputTooLarge -> {
                _state.update {
                    it.copy(connecting = false, connected = false, connectionError = "单条控制台输出过大，无法加载")
                }
                ansiParser.reset()
            }
            DaemonConsoleEvent.Gap -> {
                ansiParser.reset()
                resumeCursor = null
                panelState.markGap()
                append(LogLine("部分输出未能恢复，日志可能不完整", system = true))
            }
            is DaemonConsoleEvent.Log -> {
                val id = event.cursor?.streamId
                if (id != null && streamId != null && id != streamId) {
                    ansiParser.reset()
                    panelState.reset()
                    panelState.markGap()
                }
                if (id != null) streamId = id
                event.cursor?.let { resumeCursor = it }
                val text = event.line.take(MAX_CONSOLE_LINE_CHARS)
                if (text.length < event.line.length) {
                    panelState.markGap()
                } else {
                    text.lineSequence().forEach(panelState::onLine)
                }
                val display = if (text.length < event.line.length) "$text …（已截断）" else text
                append(LogLine(display, segments = ansiParser.append(display).ifEmpty { listOf(AnsiSegment("")) }))
                if (text.length < event.line.length) ansiParser.reset()
                if (--linesUntilYield == 0) {
                    linesUntilYield = 64
                    yield()
                }
            }
            is DaemonConsoleEvent.CommandResult -> {
                val message = if (event.success) "服务端已接收命令" else "服务端拒绝命令"
                append(LogLine(message, system = true))
            }
            DaemonConsoleEvent.EulaRequired -> _events.tryEmit(ConsoleEvent.EulaRequired)
        }
    }

    override suspend fun sendCommand(command: String): Boolean {
        val cmd = command.trim()
        val current = session
        if (cmd.isEmpty()) return false
        if (cmd.length > MAX_CONSOLE_COMMAND_CHARS || cmd.any(Char::isISOControl)) {
            _events.tryEmit(ConsoleEvent.Toast("请输入不超过 1024 字符的单行命令"))
            return false
        }
        if (!_state.value.canSendCommand || current == null) {
            _events.tryEmit(ConsoleEvent.Toast("控制台不可用，命令未发送"))
            return false
        }
        return try {
            val sent = withTimeoutOrNull(4_000) { current.sendCommand(cmd); true } == true
            if (!sent) _events.tryEmit(ConsoleEvent.Toast("命令发送结果未知，输入已保留"))
            sent
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _events.tryEmit(ConsoleEvent.Toast("命令发送失败，输入已保留"))
            false
        }
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
        val id = instanceId ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val repository = container.ensureRepository(ref.daemonId) ?: run {
                    _events.tryEmit(ConsoleEvent.Toast("Daemon 连接不可用"))
                    return@launch
                }
                if (action == "start") ensureEulaAgreed(repository, id)
                val result = repository.sendAction(id, action)
                if (result.isSuccess) {
                    _events.tryEmit(ConsoleEvent.Toast(result.getOrNull() ?: "已提交操作"))
                    loadInfo(repository, id)
                } else {
                    val error = result.exceptionOrNull()
                    val message = if (error is DaemonConnectionClosedException) {
                        "Daemon 连接已切换，操作结果未知，请刷新确认"
                    } else {
                        error?.message ?: "操作失败"
                    }
                    _events.tryEmit(ConsoleEvent.Toast(message))
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun ensureEulaAgreed(repository: InstanceRepository, id: Long) {
        val settings = repository.getSettings(id).getOrNull() ?: return
        if (settings.ignoreEula != true) return
        val propertiesPath = settings.serverPropertiesPath?.trim().orEmpty().ifBlank { "server.properties" }
        val dir = propertiesPath.substringBeforeLast('/', "")
        val eulaPath = if (dir.isBlank()) "eula.txt" else "$dir/eula.txt"
        repository.saveFileContent(id, eulaPath, EULA_AGREED_CONTENT)
    }

    override fun agreeEulaAndStart() {
        val id = instanceId ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val repository = container.ensureRepository(ref.daemonId) ?: return@launch
                val agreement = repository.sendAction(id, "agreeEula?true")
                if (agreement.isFailure) {
                    val error = agreement.exceptionOrNull()
                    val message = if (error is DaemonConnectionClosedException) {
                        "Daemon 连接已切换，EULA 操作结果未知，请刷新确认"
                    } else {
                        error?.message ?: "提交 EULA 失败"
                    }
                    _events.tryEmit(ConsoleEvent.Toast(message))
                    return@launch
                }
                val result = repository.sendAction(id, "start")
                val error = result.exceptionOrNull()
                val message = if (error is DaemonConnectionClosedException) {
                    "Daemon 连接已切换，操作结果未知，请刷新确认"
                } else {
                    result.getOrNull() ?: error?.message ?: "启动请求已提交"
                }
                _events.tryEmit(ConsoleEvent.Toast(message))
                loadInfo(repository, id)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    override fun clearLogs() {
        renderBuffer.clear()
        bufferChars = 0
        _logs.value = emptyList()
    }

    private fun append(line: LogLine) {
        renderBuffer.addLast(line)
        bufferChars += line.text.length
        while (renderBuffer.size > MAX_LOG_LINES || bufferChars > MAX_LOG_CHARS) {
            bufferChars -= renderBuffer.removeFirst().text.length
        }
        if (publishJob?.isActive == true) return
        publishJob = viewModelScope.launch {
            delay(40)
            _logs.value = renderBuffer.toList()
            panelState.publish()
        }
    }
}
