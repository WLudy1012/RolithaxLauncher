package com.wludy.rolithax.launcher.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wludy.rolithax.launcher.shared.DaemonClient
import com.wludy.rolithax.launcher.shared.DaemonUser
import com.wludy.rolithax.launcher.shared.DaemonProfile
import com.wludy.rolithax.launcher.shared.currentProfile
import com.wludy.rolithax.launcher.shared.updateProfile
import com.wludy.rolithax.launcher.shared.DaemonJava
import com.wludy.rolithax.launcher.shared.javaList
import com.wludy.rolithax.launcher.shared.recommendedJavaMajor
import com.wludy.rolithax.launcher.shared.ModrinthClient
import com.wludy.rolithax.launcher.shared.MslCoreClient
import com.wludy.rolithax.launcher.shared.MslCoreDownload
import com.wludy.rolithax.launcher.shared.ModrinthProject
import com.wludy.rolithax.launcher.shared.ModrinthVersion
import com.wludy.rolithax.launcher.shared.DaemonResourceInstallItem
import com.wludy.rolithax.launcher.shared.DaemonResourceTask
import com.wludy.rolithax.launcher.shared.installResources
import com.wludy.rolithax.launcher.shared.ResourcePlan
import com.wludy.rolithax.launcher.shared.planInstall
import com.wludy.rolithax.launcher.shared.resourceTask
import com.wludy.rolithax.launcher.shared.cancelResourceTask
import com.wludy.rolithax.launcher.shared.DaemonCreateRequest
import com.wludy.rolithax.launcher.shared.DaemonPluginsMods
import com.wludy.rolithax.launcher.shared.createInstance
import com.wludy.rolithax.launcher.shared.listPluginsMods
import com.wludy.rolithax.launcher.shared.setPluginsMods
import com.wludy.rolithax.launcher.shared.deleteInstance
import com.wludy.rolithax.launcher.shared.instanceSettings
import com.wludy.rolithax.launcher.shared.updateInstanceSettings
import com.wludy.rolithax.launcher.shared.listUsers
import com.wludy.rolithax.launcher.shared.createUser
import com.wludy.rolithax.launcher.shared.updateUser
import com.wludy.rolithax.launcher.shared.deleteUser
import com.wludy.rolithax.launcher.shared.frpList
import com.wludy.rolithax.launcher.shared.DAEMON_UPLOAD_CHUNK_SIZE
import com.wludy.rolithax.launcher.shared.beginUpload
import com.wludy.rolithax.launcher.shared.uploadChunk
import com.wludy.rolithax.launcher.shared.finishUpload
import com.wludy.rolithax.launcher.shared.discardUpload
import com.wludy.rolithax.launcher.shared.saveUpload
import com.wludy.rolithax.launcher.shared.downloadFile
import com.wludy.rolithax.launcher.shared.validRemoteLeaf
import com.wludy.rolithax.launcher.shared.createDirectory
import com.wludy.rolithax.launcher.shared.renameRemoteFile
import com.wludy.rolithax.launcher.shared.deleteRemoteFile
import com.wludy.rolithax.launcher.shared.DaemonConsole
import com.wludy.rolithax.launcher.shared.DaemonSystemMonitor
import com.wludy.rolithax.launcher.shared.SystemMonitorEvent
import com.wludy.rolithax.launcher.shared.DaemonCreationProgress
import com.wludy.rolithax.launcher.shared.CreationProgressEvent
import com.wludy.rolithax.launcher.shared.cancelCreation
import com.wludy.rolithax.launcher.shared.ConsoleEvent
import com.wludy.rolithax.launcher.shared.DaemonFile
import com.wludy.rolithax.launcher.shared.DaemonInstance
import com.wludy.rolithax.launcher.shared.DaemonInstanceInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.ktor.utils.io.readAvailable
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

internal data class ConnectionItem(val id: Long, val name: String, val displayAddress: String? = null)
internal data class UserResource(val value: String, val label: String)
internal data class DesktopState(
    val connections: List<ConnectionItem> = emptyList(),
    val savedConnections: List<SavedDaemon> = emptyList(),
    val orphanCredentialId: String? = null,
    val selectedId: Long? = null,
    val instances: List<DaemonInstance> = emptyList(),
    val instancesLoaded: Boolean = false,
    val coreChooser: Boolean = false,
    val coreNames: List<String> = emptyList(),
    val selectedCore: String? = null,
    val coreVersions: List<String> = emptyList(),
    val selectedCoreVersion: String? = null,
    val coreBuilds: List<String> = emptyList(),
    val coreDownload: MslCoreDownload? = null,
    val coreIsBedrock: Boolean = false,
    val coreJavaOptions: List<DaemonJava> = emptyList(),
    val coreJavaPath: String = "",
    val coreJavaRecommendation: Int? = null,
    val monitorConnected: Boolean = false,
    val systemStats: SystemMonitorEvent.Stats? = null,
    val creationId: String? = null,
    val creationConnected: Boolean = false,
    val creationProgress: Double = 0.0,
    val creationMessage: String = "",
    val resourceInstance: DaemonInstance? = null,
    val resourceProjects: List<ModrinthProject> = emptyList(),
    val resourceProject: ModrinthProject? = null,
    val resourceVersions: List<ModrinthVersion> = emptyList(),
    val resourceTask: DaemonResourceTask? = null,
    val resourcePlan: ResourcePlan? = null,
    val resourcePlanGameVersion: String = "",
    val resourcePlanLoader: String = "",
    val pluginsInstance: DaemonInstance? = null,
    val pluginsMode: String = "plugins",
    val pluginsData: DaemonPluginsMods? = null,
    val managingUsers: Boolean = false,
    val profile: DaemonProfile? = null,
    val users: List<DaemonUser> = emptyList(),
    val userResources: List<UserResource> = emptyList(),
    val settingsInstance: DaemonInstance? = null,
    val settingsText: String = "",
    val originalSettingsText: String = "",
    val detailInstance: DaemonInstance? = null,
    val instanceInfo: DaemonInstanceInfo? = null,
    val consoleInstance: DaemonInstance? = null,
    val consoleConnected: Boolean = false,
    val eulaRequired: Boolean = false,
    val consoleLines: List<String> = emptyList(),
    val fileInstance: DaemonInstance? = null,
    val filePath: String = "",
    val files: List<DaemonFile> = emptyList(),
    val editingPath: String? = null,
    val fileContent: String = "",
    val transferring: Boolean = false,
    val originalFileContent: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

private data class Connection(val id: Long, val name: String, val displayAddress: String, val client: DaemonClient, val console: DaemonConsole, val creation: DaemonCreationProgress, val monitor: DaemonSystemMonitor, val http: HttpClient)

internal class DesktopController(private val scope: CoroutineScope) {
    var state by mutableStateOf(DesktopState())
        private set
    private val connections = mutableListOf<Connection>()
    private val store = DesktopConnectionStore()
    private var savedGeneration = 0
    private val publicHttp = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 20_000; socketTimeoutMillis = 20_000 }
    }
    private val modrinth = ModrinthClient(publicHttp)
    private val mslCores = MslCoreClient(publicHttp)
    private var nextId = 0L
    private var job: Job? = null
    private var consoleJob: Job? = null
    private var creationJob: Job? = null
    private var monitorJob: Job? = null
    private var statusRefreshJob: Job? = null

    fun connect(name: String, address: String, key: String, allowHttp: Boolean, fingerprint: String) = connect(name, address, key, allowHttp, fingerprint, true)

    private fun connect(name: String, address: String, key: String, allowHttp: Boolean, fingerprint: String, persist: Boolean) {
        if (state.busy) return
        val trimmedAddress = address.trim()
        if (trimmedAddress.isEmpty() || key.isBlank()) {
            state = state.copy(error = "请填写 Daemon 地址和 API Key。")
            return
        }
        val pin = fingerprint.trim().replace(":", "").lowercase()
        if (pin.isNotEmpty() && (!pin.matches(Regex("[0-9a-f]{64}")) || trimmedAddress.startsWith("http://", true))) {
            state = state.copy(error = "证书指纹须为 64 位 SHA-256 十六进制值，且只能用于 HTTPS。")
            return
        }
        val http = HttpClient(CIO) {
            if (pin.isNotEmpty()) engine { https { trustManager = PinnedCertificateTrustManager(pin) } }
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
        }
        val client = try {
            DaemonClient(trimmedAddress, key.trim(), http, allowHttp)
        } catch (e: Exception) {
            http.close()
            state = state.copy(error = "地址无效，请检查协议和地址；HTTP 需要明确允许。")
            return
        }
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            var retained = false
            try {
                val instances = client.listInstances()
                val console = DaemonConsole(trimmedAddress, key.trim(), http, allowHttp)
                val creation = DaemonCreationProgress(trimmedAddress, key.trim(), http, allowHttp)
                val monitor = DaemonSystemMonitor(trimmedAddress, key.trim(), http, allowHttp)
                val connection = Connection(++nextId, name.trim().ifBlank { displayDaemonAddress(trimmedAddress) }, displayDaemonAddress(trimmedAddress), client, console, creation, monitor, http)
                creationJob?.cancel()
                consoleJob?.cancel()
                connections += connection
                retained = true
                state = state.copy(
                    connections = connections.map { ConnectionItem(it.id, it.name, it.displayAddress) },
                    selectedId = connection.id,
                    instances = instances,
                    instancesLoaded = true,
                    coreChooser = false,
                    monitorConnected = false,
                    systemStats = null,
                    creationId = null,
                    creationConnected = false,
                    resourceInstance = null,
                    pluginsInstance = null,
                    managingUsers = false,
                    profile = null,
                    settingsInstance = null,
                    detailInstance = null,
                    instanceInfo = null,
                    consoleInstance = null,
                    consoleConnected = false,
                    eulaRequired = false,
                    consoleLines = emptyList(),
                    fileInstance = null,
                    editingPath = null,
                    notice = "连接成功",
                )
                followSystem(connection)
                if (persist && state.orphanCredentialId != null) {
                    state = state.copy(notice = "请先清理先前残留的系统密钥；连接仅在当前会话有效。")
                } else if (persist) {
                    try {
                        val saved = runInterruptible(Dispatchers.IO) { store.add(connection.name, trimmedAddress, allowHttp, pin, key.trim()) }
                        savedGeneration++
                        state = state.copy(savedConnections = state.savedConnections + saved)
                    } catch (e: CancellationException) { throw e } catch (e: OrphanedCredentialException) {
                        state = state.copy(orphanCredentialId = e.credentialId, error = "连接仅在当前会话有效；系统密钥可能残留，请清理。")
                    } catch (e: Exception) {
                        state = state.copy(notice = "系统密钥存储不可用，连接仅在当前会话有效。")
                    } catch (e: LinkageError) {
                        state = state.copy(notice = "系统密钥存储不可用，连接仅在当前会话有效。")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(error = "连接失败，请检查地址、证书、API Key 和 Daemon 状态。")
            } finally {
                if (!retained) {
                    client.close()
                    http.close()
                }
                state = state.copy(busy = false)
            }
        }
    }

    fun loadSaved() {
        val generation = savedGeneration
        scope.launch {
            try {
                val saved = runInterruptible(Dispatchers.IO) { store.list() }
                if (generation == savedGeneration) state = state.copy(savedConnections = saved)
            }
            catch (e: CancellationException) { throw e } catch (e: Exception) { state = state.copy(error = "无法读取已保存的连接。") }
        }
    }

    fun connectSaved(saved: SavedDaemon) {
        if (state.busy) return
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                val key = runInterruptible(Dispatchers.IO) { store.password(saved.id) }
                state = state.copy(busy = false)
                connect(saved.name, saved.address, key, saved.allowHttp, saved.fingerprint, false)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(busy = false, error = "无法从系统密钥存储读取该连接，请检查系统密钥库。")
            } catch (e: LinkageError) {
                state = state.copy(busy = false, error = "系统密钥存储不可用。")
            }
        }
    }

    fun removeSaved(saved: SavedDaemon) {
        if (state.busy) return
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                runInterruptible(Dispatchers.IO) { store.remove(saved.id) }
                savedGeneration++
                state = state.copy(savedConnections = state.savedConnections.filterNot { it.id == saved.id }, notice = "已删除保存的连接。")
            } catch (e: CancellationException) { throw e } catch (e: CredentialRestoreException) {
                state = state.copy(error = "连接删除未完成且密钥恢复失败；请检查系统密钥库，标识：" + e.credentialId)
            } catch (e: Exception) {
                state = state.copy(error = "删除连接或系统密钥失败，请检查系统密钥库和配置目录。")
            } catch (e: LinkageError) {
                state = state.copy(error = "系统密钥存储不可用，无法删除该密钥。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun cleanupOrphan() {
        val id = state.orphanCredentialId ?: return
        if (state.busy) return
        state = state.copy(busy = true)
        job = scope.launch {
            try {
                runInterruptible(Dispatchers.IO) { store.cleanupOrphan(id) }
                state = state.copy(orphanCredentialId = null, error = null, notice = "已清理系统密钥。")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "无法清理系统密钥，请检查系统密钥库。")
            } catch (e: LinkageError) {
                state = state.copy(error = "系统密钥存储不可用，无法清理。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun select(id: Long) {
        if (state.busy) return
        statusRefreshJob?.cancel()
        consoleJob?.cancel()
        creationJob?.cancel()
        state = state.copy(selectedId = id, instances = emptyList(), instancesLoaded = false, coreChooser = false, monitorConnected = false, systemStats = null, creationId = null, creationConnected = false, resourceInstance = null, pluginsInstance = null, managingUsers = false, profile = null, settingsInstance = null, detailInstance = null, instanceInfo = null, consoleInstance = null, consoleConnected = false, eulaRequired = false, consoleLines = emptyList(), fileInstance = null, editingPath = null, error = null, notice = null)
        connections.firstOrNull { it.id == id }?.let(::followSystem)
        refresh()
    }

    fun remove(id: Long) {
        if (state.busy) return
        val connection = connections.firstOrNull { it.id == id } ?: return
        if (state.selectedId != id) {
            connections.remove(connection)
            connection.client.close()
            connection.http.close()
            state = state.withoutInactiveConnection(id)
            return
        }
        statusRefreshJob?.cancel()
        consoleJob?.cancel()
        creationJob?.cancel()
        monitorJob?.cancel()
        connections.remove(connection)
        connection.client.close()
        connection.http.close()
        val next = connections.firstOrNull()
        state = state.copy(
            connections = connections.map { ConnectionItem(it.id, it.name, it.displayAddress) },
            selectedId = next?.id,
            instances = emptyList(), instancesLoaded = false, coreChooser = false, monitorConnected = false, systemStats = null, creationId = null, creationConnected = false, resourceInstance = null, pluginsInstance = null, managingUsers = false, profile = null, settingsInstance = null, detailInstance = null, instanceInfo = null, consoleInstance = null, consoleConnected = false, eulaRequired = false, consoleLines = emptyList(), fileInstance = null, editingPath = null, error = null, notice = null,
        )
        if (next != null) { followSystem(next); refresh() }
    }

    private fun followSystem(connection: Connection) {
        val previous = monitorJob
        previous?.cancel()
        monitorJob = scope.launch {
            try {
                previous?.join()
                connection.monitor.run { event ->
                    if (state.selectedId != connection.id) return@run
                    when (event) {
                        SystemMonitorEvent.Connected -> state = state.copy(monitorConnected = true)
                        SystemMonitorEvent.Disconnected -> state = state.copy(monitorConnected = false)
                        is SystemMonitorEvent.Stats -> state = state.copy(systemStats = event)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (state.selectedId == connection.id) state = state.copy(monitorConnected = false)
            }
        }
    }

    fun refresh() {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                state = state.withInstances(selected.client.listInstances())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(instancesLoaded = false, error = "刷新失败，请检查连接后重试。")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    fun openProfile() {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                val profile = selected.client.currentProfile()
                if (state.selectedId == selected.id) state = state.copy(profile = profile)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (state.selectedId == selected.id) state = state.copy(error = "无法读取当前用户信息。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun saveProfile(username: String, name: String, avatar: String, oldPassword: String, password: String) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy || state.profile == null) return
        if (password.isNotBlank() && oldPassword.isBlank()) {
            state = state.copy(error = "修改密码时必须填写当前密码。")
            return
        }
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                selected.client.updateProfile(username.trim().ifBlank { null }, name.trim().ifBlank { null }, avatar.trim().ifBlank { null }, oldPassword.takeIf { password.isNotBlank() }, password.ifBlank { null })
                if (state.selectedId == selected.id) state = state.copy(profile = null, notice = "用户信息已更新。")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (state.selectedId == selected.id) state = state.copy(error = "更新用户信息失败，请检查当前密码和权限。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun closeProfile() { if (!state.busy) state = state.copy(profile = null) }

    fun action(instance: DaemonInstance, action: String) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        statusRefreshJob?.cancel()
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                selected.client.action(instance.id, action)
                state = state.copy(notice = "操作已提交。")
                try {
                    state = state.withInstances(selected.client.listInstances())
                    followInstanceStatus(selected)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state = state.copy(instancesLoaded = false, error = "操作已提交，但刷新实例状态失败，请检查连接后重试。")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(error = "操作失败，请检查实例状态及权限。")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    private fun followInstanceStatus(connection: Connection) {
        statusRefreshJob?.cancel()
        statusRefreshJob = scope.launch {
            repeat(8) {
                delay(1_500)
                if (state.selectedId != connection.id) return@launch
                if (!state.busy) {
                    try {
                        val instances = connection.client.listInstances()
                        if (state.selectedId == connection.id && !state.busy) state = state.withInstances(instances)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (state.selectedId == connection.id) state = state.copy(instancesLoaded = false, error = "实例状态自动刷新失败，请检查连接后重试。")
                        return@launch
                    }
                }
            }
        }
    }

    fun openResources(instance: DaemonInstance) {
        if (state.busy) return
        consoleJob?.cancel()
        state = state.copy(resourceInstance = instance, resourceProjects = emptyList(), resourceProject = null, resourceVersions = emptyList(), resourceTask = null, resourcePlan = null, error = null, notice = null)
    }

    fun searchResources(query: String, type: String, gameVersion: String, loader: String) {
        if (state.busy || state.resourceInstance == null) return
        state = state.copy(busy = true, resourceProjects = emptyList(), resourceProject = null, resourceVersions = emptyList(), resourcePlan = null, error = null, notice = null)
        job = scope.launch {
            try { state = state.copy(resourceProjects = modrinth.search(query, type, gameVersion, loader)) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { state = state.copy(error = "资源搜索失败，请检查网络。") }
            finally { state = state.copy(busy = false) }
        }
    }

    fun selectResource(project: ModrinthProject, gameVersion: String, loader: String) {
        if (state.busy || state.resourceInstance == null) return
        state = state.copy(busy = true, resourceProject = project, resourceVersions = emptyList(), resourcePlan = null, error = null, notice = null)
        job = scope.launch {
            try { state = state.copy(resourceVersions = modrinth.versions(project.id, gameVersion, loader)) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { state = state.copy(error = "读取资源版本失败。") }
            finally { state = state.copy(busy = false) }
        }
    }

    fun inspectResource(version: ModrinthVersion, type: String, gameVersion: String, loader: String) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val instance = state.resourceInstance ?: return
        val project = state.resourceProject ?: return
        if (state.busy) return
        if (gameVersion.isBlank() || loader.isBlank()) { state = state.copy(error = "请填写游戏版本和加载器。"); return }
        state = state.copy(busy = true, resourcePlan = null, error = null, notice = null)
        job = scope.launch {
            try {
                val plan = modrinth.planInstall(project, version, type, gameVersion, loader)
                val existingByType = plan.entries.map { it.type }.distinct().associateWith { kind ->
                    val files = selected.client.listPluginsMods(instance.id, if (kind == "plugin") "plugins" else "mods")
                    (files.jarFiles + files.clientJarFiles + files.disableJarFiles).map { it.lowercase().removeSuffix(".disabled") }.toSet()
                }
                if (plan.entries.any { entry -> entry.version.files.any { it.filename.lowercase().removeSuffix(".disabled") in existingByType[entry.type].orEmpty() } }) {
                    throw IllegalStateException("目标文件与已有插件或模组重名")
                }
                state = state.copy(resourcePlan = plan, resourcePlanGameVersion = gameVersion, resourcePlanLoader = loader)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "依赖、兼容性或已有文件检查失败，请检查资源和实例状态。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun dismissResourcePlan() { state = state.copy(resourcePlan = null) }

    fun installResourcePlan() {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val instance = state.resourceInstance ?: return
        val plan = state.resourcePlan ?: return
        if (state.busy) return
        val gameVersion = state.resourcePlanGameVersion
        val loader = state.resourcePlanLoader
        state = state.copy(busy = true, resourcePlan = null, error = null, notice = null)
        job = scope.launch {
            try {
                val items = plan.entries.map { DaemonResourceInstallItem(it.project.id, it.version.id, it.type) }
                state = state.copy(resourceTask = selected.client.installResources(instance.id, gameVersion, loader, items), notice = "安装任务已提交。")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "提交安装失败，请检查 Daemon 插件和兼容性。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun cancelResourceTask() {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val task = state.resourceTask ?: return
        if (state.busy) return
        state = state.copy(busy = true, error = null)
        job = scope.launch {
            try {
                selected.client.cancelResourceTask(task.id)
                state = state.copy(notice = "已请求取消安装。")
                try {
                    state = state.copy(resourceTask = selected.client.resourceTask(task.id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state = state.copy(error = "取消请求已提交，但任务状态刷新失败。")
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { state = state.copy(error = "取消安装任务失败。") }
            finally { state = state.copy(busy = false) }
        }
    }

    fun refreshResourceTask() {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val task = state.resourceTask ?: return
        if (state.busy) return
        state = state.copy(busy = true, error = null)
        job = scope.launch {
            try { state = state.copy(resourceTask = selected.client.resourceTask(task.id)) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { state = state.copy(error = "查询安装任务失败。") }
            finally { state = state.copy(busy = false) }
        }
    }

    fun closeResources() { if (!state.busy) state = state.copy(resourceInstance = null, resourceProjects = emptyList(), resourceProject = null, resourceVersions = emptyList(), resourceTask = null, error = null, notice = null) }

    fun openCoreChooser() {
        if (state.busy || state.selectedId == null) return
        state = state.copy(busy = true, coreChooser = true, coreNames = emptyList(), selectedCore = null, coreVersions = emptyList(), selectedCoreVersion = null, coreBuilds = emptyList(), coreDownload = null, coreJavaOptions = emptyList(), coreJavaPath = "", coreJavaRecommendation = null, error = null)
        job = scope.launch {
            try { state = state.copy(coreNames = mslCores.cores()) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { state = state.copy(error = "加载在线核心失败。") }
            finally { state = state.copy(busy = false) }
        }
    }

    fun chooseCore(core: String) {
        if (state.busy || !state.coreChooser) return
        state = state.copy(busy = true, selectedCore = core, coreVersions = emptyList(), selectedCoreVersion = null, coreBuilds = emptyList(), coreDownload = null, error = null)
        job = scope.launch {
            try { state = state.copy(coreVersions = mslCores.versions(core)) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { state = state.copy(error = "加载核心版本失败。") }
            finally { state = state.copy(busy = false) }
        }
    }

    fun chooseCoreVersion(version: String) {
        val core = state.selectedCore ?: return
        if (state.busy) return
        state = state.copy(busy = true, selectedCoreVersion = version, coreBuilds = emptyList(), coreDownload = null, error = null)
        job = scope.launch {
            try { state = state.copy(coreBuilds = mslCores.builds(core, version)) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { state = state.copy(error = "加载核心构建版本失败。") }
            finally { state = state.copy(busy = false) }
        }
    }

    fun chooseCoreBuild(build: String) {
        val core = state.selectedCore ?: return
        val version = state.selectedCoreVersion ?: return
        if (state.busy) return
        state = state.copy(busy = true, coreDownload = null, error = null)
        job = scope.launch {
            try {
                val download = mslCores.download(core, version, build)
                val bedrock = mslCores.isBedrock(core)
                val recommendation = if (bedrock) null else recommendedJavaMajor(version)
                val selected = connections.firstOrNull { it.id == state.selectedId } ?: return@launch
                val options = if (bedrock) emptyList() else try { selected.client.javaList() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                val preferred = options.firstOrNull { javaMajor(it.version) == recommendation && it.path.isNotBlank() }?.path.orEmpty()
                state = state.copy(coreDownload = download, coreIsBedrock = bedrock, coreJavaRecommendation = recommendation, coreJavaOptions = options, coreJavaPath = preferred)
            }
            catch (e: CancellationException) { throw e } catch (e: Exception) { state = state.copy(error = "获取核心下载信息失败，或缺少完整 SHA-256。") }
            finally { state = state.copy(busy = false) }
        }
    }

    fun createOnline(name: String, minMemory: String, maxMemory: String) {
        val download = state.coreDownload ?: return
        val min = minMemory.toIntOrNull()
        val max = maxMemory.toIntOrNull()
        if (name.isBlank() || min == null || max == null || min <= 0 || max < min) { state = state.copy(error = "请填写名称和有效的内存范围。"); return }
        val bedrock = state.coreIsBedrock
        val java = if (bedrock) null else state.coreJavaPath.ifBlank { null }
        state = state.copy(coreChooser = false)
        submitCreate(DaemonCreateRequest(name.trim(), if (bedrock) "bedrock_server.jar" else "server.jar", min, max, java = java, coreUrl = download.url, coreSha256 = download.sha256, coreMirrors = download.mirrors))
    }

    fun chooseCoreJava(path: String) { if (!state.busy) state = state.copy(coreJavaPath = path) }

    fun closeCoreChooser() { if (!state.busy) state = state.copy(coreChooser = false, coreDownload = null, error = null) }

    fun createInstance(name: String, core: String, minMemory: String, maxMemory: String, java: String, args: String, path: String) {
        if (state.busy) return
        val min = minMemory.toIntOrNull()
        val max = maxMemory.toIntOrNull()
        if (name.isBlank() || core.isBlank() || min == null || max == null || min <= 0 || max < min) {
            state = state.copy(error = "请填写名称、核心文件名及有效的内存范围。")
            return
        }
        submitCreate(DaemonCreateRequest(name.trim(), core.trim(), min, max, java.ifBlank { null }, args.ifBlank { null }, path.ifBlank { null }))
    }

    fun createAdvanced(content: String) {
        val request = try { Json.decodeFromString<DaemonCreateRequest>(content) } catch (e: Exception) {
            state = state.copy(error = "创建参数 JSON 无效，请检查字段和值。")
            return
        }
        submitCreate(request)
    }

    private fun submitCreate(request: DaemonCreateRequest) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                val serverId = selected.client.createInstance(request)
                state = state.copy(creationId = serverId, creationProgress = 0.0, creationMessage = "等待进度", notice = "创建任务已提交。")
                followCreation(selected, serverId)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "创建任务提交失败，请检查 Daemon 和参数。")
            } finally { state = state.copy(busy = false) }
        }
    }

    private fun followCreation(connection: Connection, serverId: String) {
        val previous = creationJob
        previous?.cancel()
        creationJob = scope.launch {
            try {
                previous?.join()
                connection.creation.run(serverId) { event ->
                    if (state.selectedId != connection.id || state.creationId != serverId) return@run
                    when (event) {
                        CreationProgressEvent.Connected -> state = state.copy(creationConnected = true)
                        CreationProgressEvent.Disconnected -> state = state.copy(creationConnected = false)
                        is CreationProgressEvent.Update -> state = state.copy(creationProgress = event.progress, creationMessage = event.message)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (state.selectedId == connection.id && state.creationId == serverId) state = state.copy(creationConnected = false, error = "创建进度监控失败。")
            }
        }
    }

    fun cancelCreation() {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val serverId = state.creationId ?: return
        if (state.busy) return
        state = state.copy(busy = true, error = null)
        job = scope.launch {
            try {
                selected.client.cancelCreation(serverId)
                state = state.copy(notice = "已请求取消创建任务。")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "取消创建任务失败。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun closeCreation() {
        creationJob?.cancel()
        state = state.copy(creationId = null, creationConnected = false, creationMessage = "")
    }

    fun openPlugins(instance: DaemonInstance, mode: String) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        consoleJob?.cancel()
        state = state.copy(busy = true, pluginsInstance = instance, pluginsMode = mode, pluginsData = null, error = null, notice = null)
        job = scope.launch {
            try {
                state = state.copy(pluginsData = selected.client.listPluginsMods(instance.id, mode))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "读取插件或模组失败。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun changePlugin(target: String, action: String) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val instance = state.pluginsInstance ?: return
        val mode = state.pluginsMode
        if (state.busy) return
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                selected.client.setPluginsMods(instance.id, mode, action, listOf(target))
                state = state.copy(pluginsData = selected.client.listPluginsMods(instance.id, mode), notice = "操作已提交。")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "操作或刷新失败，请检查插件或模组状态。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun closePlugins() { if (!state.busy) state = state.copy(pluginsInstance = null, pluginsData = null, error = null, notice = null) }

    fun deleteInstance(instance: DaemonInstance, deleteFiles: Boolean) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                selected.client.deleteInstance(instance.id, deleteFiles)
                state = state.withInstances(selected.client.listInstances()).copy(notice = "实例已删除。")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(error = "删除或刷新失败，请确认实例状态后重试。")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    fun openSettings(instance: DaemonInstance) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        state = state.copy(busy = true, settingsInstance = instance, detailInstance = null, error = null, notice = null)
        job = scope.launch {
            try {
                val settings = selected.client.instanceSettings(instance.id).toString()
                state = state.copy(settingsText = settings, originalSettingsText = settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(settingsInstance = null, error = "读取实例设置失败。")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    fun editSettings(value: String) { state = state.copy(settingsText = value) }

    fun saveSettings() {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val instance = state.settingsInstance ?: return
        if (state.busy) return
        val content = state.settingsText
        val settings = try { Json.parseToJsonElement(content) as? JsonObject } catch (e: Exception) { null }
        if (settings == null) {
            state = state.copy(error = "设置必须是有效的 JSON 对象。")
            return
        }
        val memoryError = validateMemorySettings(settings)
        if (memoryError != null) {
            state = state.copy(error = memoryError)
            return
        }
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                selected.client.updateInstanceSettings(instance.id, settings)
                state = state.copy(originalSettingsText = content, notice = "实例设置已保存，部分修改可能需要重启。")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(error = "保存实例设置失败。")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    fun closeSettings() {
        if (!state.busy) state = state.copy(settingsInstance = null, settingsText = "", originalSettingsText = "", error = null, notice = null)
    }

    fun openUsers() {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        consoleJob?.cancel()
        state = state.copy(busy = true, managingUsers = true, users = emptyList(), consoleInstance = null, fileInstance = null, detailInstance = null, settingsInstance = null, error = null, notice = null)
        job = scope.launch {
            try {
                val users = selected.client.listUsers()
                val instances = selected.client.listInstances()
                val frps = selected.client.frpList()
                val resources = instances.map { UserResource("server:" + it.id, "实例：" + (it.name ?: it.id.toString())) } + frps.map { UserResource("frp:" + it.id, "FRP：" + (it.name ?: it.id.toString())) }
                state = state.copy(users = users, userResources = resources)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(error = "读取用户列表失败，请检查管理员权限。")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    fun createUser(username: String, password: String, name: String, role: String, resources: List<String>) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        if (username.isBlank() || password.isBlank()) { state = state.copy(error = "用户名和密码不能为空。"); return }
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                selected.client.createUser(username.trim(), password, name.trim().ifBlank { null }, role, resources)
                state = state.copy(users = selected.client.listUsers(), notice = "用户已创建。")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "创建用户或刷新列表失败。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun updateUser(user: DaemonUser, name: String, password: String, role: String, resources: List<String>, resetApiKey: Boolean) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val id = user.id ?: return
        if (state.busy) return
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                selected.client.updateUser(id, name.trim().ifBlank { null }, password.ifBlank { null }, role, resources, resetApiKey)
                state = state.copy(users = selected.client.listUsers(), notice = "用户已更新。")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "更新用户或刷新列表失败。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun deleteUser(user: DaemonUser) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val id = user.id ?: return
        if (state.busy) return
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                selected.client.deleteUser(id)
                state = state.copy(users = selected.client.listUsers(), notice = "用户已删除。")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "删除用户或刷新列表失败。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun closeUsers() { if (!state.busy) state = state.copy(managingUsers = false, users = emptyList(), error = null, notice = null) }

    fun openDetails(instance: DaemonInstance) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        consoleJob?.cancel()
        state = state.copy(busy = true, detailInstance = instance, instanceInfo = null, consoleInstance = null, fileInstance = null, error = null, notice = null)
        job = scope.launch {
            try {
                state = state.copy(instanceInfo = selected.client.instanceInfo(instance.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(error = "读取实例详情失败。")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    fun closeDetails() {
        if (!state.busy) state = state.copy(detailInstance = null, instanceInfo = null, error = null, notice = null)
    }

    fun openConsole(instance: DaemonInstance) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        val previous = consoleJob
        previous?.cancel()
        state = state.copy(detailInstance = null, instanceInfo = null, consoleInstance = instance, consoleConnected = false, eulaRequired = false, consoleLines = emptyList(), fileInstance = null, editingPath = null, error = null, notice = null)
        consoleJob = scope.launch {
            try {
                previous?.join()
                selected.console.run(instance.id) { event ->
                    when (event) {
                        ConsoleEvent.Connected -> state = state.copy(consoleConnected = true)
                        ConsoleEvent.Disconnected -> state = state.copy(consoleConnected = false)
                        ConsoleEvent.Gap -> addConsoleLine("控制台重连，部分日志可能缺失。")
                        is ConsoleEvent.Log -> addConsoleLine(event.line)
                        is ConsoleEvent.CommandResult -> addConsoleLine(event.message ?: if (event.success) "命令已发送" else "命令发送失败")
                        ConsoleEvent.EulaRequired -> state = state.copy(eulaRequired = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(consoleConnected = false, error = "控制台连接失败。")
            }
        }
    }

    private fun addConsoleLine(line: String) {
        state = state.copy(consoleLines = (state.consoleLines + line).takeLast(1000))
    }

    fun sendCommand(command: String) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val value = command.trim()
        val instanceId = state.consoleInstance?.id ?: return
        if (!state.consoleConnected || value.isEmpty()) return
        scope.launch {
            try {
                selected.console.sendCommand(value)
                if (state.selectedId == selected.id && state.consoleInstance?.id == instanceId) addConsoleLine("> " + value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (state.selectedId == selected.id && state.consoleInstance?.id == instanceId) state = state.copy(error = "命令发送失败，请检查控制台连接。")
            }
        }
    }

    fun dismissEula() { state = state.copy(eulaRequired = false) }

    fun agreeEulaAndStart() {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val instance = state.consoleInstance ?: return
        if (state.busy) return
        state = state.copy(busy = true, eulaRequired = false, error = null, notice = null)
        job = scope.launch {
            try {
                selected.client.action(instance.id, "agreeEula?true")
                selected.client.action(instance.id, "start")
                state = state.copy(notice = "已同意 EULA 并提交启动请求。")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "同意 EULA 或启动失败，请检查实例状态。")
            } finally { state = state.copy(busy = false) }
        }
    }

    fun closeConsole() {
        consoleJob?.cancel()
        state = state.copy(consoleInstance = null, consoleConnected = false, eulaRequired = false, consoleLines = emptyList(), error = null)
    }

    fun uploadFile(file: File) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val instance = state.fileInstance ?: return
        if (state.busy || !file.isFile || !validRemoteLeaf(file.name)) return
        val path = state.filePath
        state = state.copy(busy = true, transferring = true, error = null, notice = null)
        job = scope.launch {
            var uploadId: String? = null
            var saved = false
            try {
                if (selected.client.listFiles(instance.id, path).any { it.name.equals(file.name, true) }) throw IllegalStateException("目标文件已存在")
                uploadId = selected.client.beginUpload()
                val id = uploadId
                val count = withContext(Dispatchers.IO) {
                    var index = 0
                    file.inputStream().use { input ->
                        val buffer = ByteArray(DAEMON_UPLOAD_CHUNK_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            selected.client.uploadChunk(id, index++, buffer.copyOf(read))
                        }
                    }
                    if (index == 0) { selected.client.uploadChunk(id, 0, ByteArray(0)); index = 1 }
                    index
                }
                selected.client.finishUpload(id, count)
                if (selected.client.listFiles(instance.id, path).any { it.name.equals(file.name, true) }) throw IllegalStateException("目标文件已存在")
                selected.client.saveUpload(instance.id, id, file.name, path)
                saved = true
                state = state.copy(files = selected.client.listFiles(instance.id, path), notice = "文件上传完成。")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "上传或刷新失败，请检查权限、目标文件及连接。")
            } finally {
                val id = uploadId
                if (!saved && id != null) withContext(NonCancellable) { withTimeoutOrNull(5_000) { try { selected.client.discardUpload(id) } catch (_: Exception) {} } }
                state = state.copy(busy = false, transferring = false)
            }
        }
    }

    fun downloadFile(remote: DaemonFile, destination: File) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val instance = state.fileInstance ?: return
        if (state.busy || remote.type == "folder" || !validRemoteLeaf(remote.name)) return
        val remotePath = listOf(state.filePath, remote.name).filter { it.isNotEmpty() }.joinToString("/")
        state = state.copy(busy = true, transferring = true, error = null, notice = null)
        job = scope.launch {
            var temporary: java.nio.file.Path? = null
            try {
                withContext(Dispatchers.IO) {
                    val target = destination.toPath().toAbsolutePath()
                    require(!Files.exists(target)) { "目标文件已存在" }
                    val parent = target.parent ?: throw IllegalArgumentException("目标目录无效")
                    val temp = Files.createTempFile(parent, ".rolithax-", ".part")
                    temporary = temp
                    Files.newOutputStream(temp).use { output ->
                        selected.client.downloadFile(instance.id, remotePath) { channel, expected ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = channel.readAvailable(buffer, 0, buffer.size)
                                if (read < 0) break
                                if (read == 0) continue
                                output.write(buffer, 0, read)
                                count += read
                            }
                            if (expected != null && count != expected) throw IllegalStateException("下载长度不匹配")
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    Files.move(temp, target)
                    temporary = null
                }
                state = state.copy(notice = "下载完成；服务器未提供可信摘要，文件完整性未验证。")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "下载失败，请检查目标路径、目标文件是否已存在和连接。")
            } finally {
                temporary?.let { temp -> withContext(NonCancellable + Dispatchers.IO) { try { Files.deleteIfExists(temp) } catch (_: Exception) {} } }
                state = state.copy(busy = false, transferring = false)
            }
        }
    }

    fun cancelTransfer() { if (state.transferring) job?.cancel() }

    fun createDirectory(name: String) {
        if (!validRemoteLeaf(name)) {
            state = state.copy(error = "目录名称无效。")
            return
        }
        mutateFiles { client, instance, path -> client.createDirectory(instance.id, path, name) }
    }

    fun renameRemoteFile(file: DaemonFile, newName: String) {
        if (!validRemoteLeaf(file.name) || !validRemoteLeaf(newName)) {
            state = state.copy(error = "文件名称无效。")
            return
        }
        if (file !in state.files || file.name == newName) return
        mutateFiles { client, instance, path ->
            client.renameRemoteFile(instance.id, remoteChild(path, file.name), remoteChild(path, newName))
        }
    }

    fun deleteRemoteFile(file: DaemonFile) {
        if (!validRemoteLeaf(file.name) || file !in state.files) return
        mutateFiles { client, instance, path -> client.deleteRemoteFile(instance.id, remoteChild(path, file.name)) }
    }

    private fun remoteChild(path: String, name: String) = listOf(path, name).filter { it.isNotEmpty() }.joinToString("/")

    private fun mutateFiles(action: suspend (DaemonClient, DaemonInstance, String) -> String?) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val instance = state.fileInstance ?: return
        if (state.busy || state.editingPath != null) return
        val path = state.filePath
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                val message = action(selected.client, instance, path)
                state = state.copy(notice = message ?: "操作已提交。")
                try {
                    state = state.copy(files = selected.client.listFiles(instance.id, path))
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    state = state.copy(error = "操作已提交，但刷新目录失败。")
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = state.copy(error = "文件操作失败，请检查权限和目标名称。")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    fun browseFiles(instance: DaemonInstance, path: String = "") {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        if (state.busy) return
        consoleJob?.cancel()
        state = state.copy(busy = true, detailInstance = null, instanceInfo = null, consoleInstance = null, consoleConnected = false, eulaRequired = false, consoleLines = emptyList(), fileInstance = instance, filePath = path, files = emptyList(), editingPath = null, error = null, notice = null)
        job = scope.launch {
            try {
                state = state.copy(files = selected.client.listFiles(instance.id, path))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(error = "读取文件列表失败。")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    fun openFile(file: DaemonFile) {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val instance = state.fileInstance ?: return
        if (state.busy || file.name in setOf("", ".", "..") || '/' in file.name || '\\' in file.name) return
        val path = listOf(state.filePath, file.name).filter { it.isNotEmpty() }.joinToString("/")
        if (file.type == "folder") {
            browseFiles(instance, path)
            return
        }
        val suffix = file.name.substringAfterLast('.', "").lowercase()
        if (suffix !in setOf("txt", "json", "yml", "yaml", "properties", "cfg", "conf", "toml", "ini", "xml", "md", "log", "sh", "bat", "csv")) {
            state = state.copy(error = "仅支持在线编辑常见文本文件。")
            return
        }
        if (file.size > 512 * 1024) {
            state = state.copy(error = "文件超过 512 KiB，暂不支持在线编辑。")
            return
        }
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                val content = selected.client.fileContent(instance.id, path)
                state = state.copy(editingPath = path, fileContent = content, originalFileContent = content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(error = "读取文件失败。")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    fun editContent(value: String) {
        state = state.copy(fileContent = value)
    }

    fun saveFile() {
        val selected = connections.firstOrNull { it.id == state.selectedId } ?: return
        val instance = state.fileInstance ?: return
        val path = state.editingPath ?: return
        if (state.busy) return
        val content = state.fileContent
        state = state.copy(busy = true, error = null, notice = null)
        job = scope.launch {
            try {
                selected.client.saveFileContent(instance.id, path, content)
                state = state.copy(notice = "文件已保存。", originalFileContent = content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(error = "保存文件失败。")
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    fun closeFiles() {
        if (!state.busy) state = state.copy(fileInstance = null, editingPath = null, files = emptyList(), error = null, notice = null)
    }

    fun closeEditor() {
        if (!state.busy) state = state.copy(editingPath = null, fileContent = "", error = null, notice = null)
    }

    fun close() {
        statusRefreshJob?.cancel()
        job?.cancel()
        consoleJob?.cancel()
        creationJob?.cancel()
        monitorJob?.cancel()
        connections.forEach { it.client.close(); it.http.close() }
        connections.clear()
        modrinth.close()
        mslCores.close()
        publicHttp.close()
    }
}

internal fun DesktopState.withInstances(updated: List<DaemonInstance>): DesktopState {
    val byId = updated.associateBy { it.id }
    fun latest(instance: DaemonInstance?) = instance?.let { byId[it.id] ?: it }
    val info = instanceInfo?.let { current ->
        byId[current.id]?.let { current.copy(status = it.status, statusText = it.statusText) } ?: current
    }
    return copy(
        instances = updated,
        instancesLoaded = true,
        resourceInstance = latest(resourceInstance),
        pluginsInstance = latest(pluginsInstance),
        settingsInstance = latest(settingsInstance),
        detailInstance = latest(detailInstance),
        instanceInfo = info,
        consoleInstance = latest(consoleInstance),
        fileInstance = latest(fileInstance),
    )
}

internal fun DesktopState.withoutInactiveConnection(id: Long): DesktopState {
    require(id != selectedId)
    return copy(connections = connections.filterNot { it.id == id })
}

internal fun validateMemorySettings(settings: JsonObject): String? {
    fun read(key: String): Int? {
        val value = settings[key] as? JsonPrimitive ?: return null
        return if (value.isString || value is JsonNull) null else value.intOrNull
    }
    for (key in listOf("minM", "maxM")) {
        val value = settings[key]
        val number = read(key)
        if (value != null && value !is JsonNull && (number == null || number < 0)) {
            return "最小和最大内存必须是非负整数。"
        }
    }
    val min = read("minM")
    val max = read("maxM")
    return if (min != null && max != null && max < min) "最大内存不能小于最小内存。" else null
}

private class PinnedCertificateTrustManager(private val expectedFingerprint: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("客户端证书不可用")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("缺少服务器证书")
        certificate.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (!MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), expectedFingerprint.toByteArray(Charsets.US_ASCII))) {
            throw CertificateException("服务器证书指纹不匹配")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun javaMajor(version: String): Int? {
    val numbers = Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
    if (numbers.isEmpty()) return null
    return if (numbers.first() == 1) numbers.getOrNull(1) else numbers.first()
}
