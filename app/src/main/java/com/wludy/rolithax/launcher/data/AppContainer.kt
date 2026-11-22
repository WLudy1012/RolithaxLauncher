package com.wludy.rolithax.launcher.data

import android.content.Context
import androidx.room.Room
import com.wludy.rolithax.launcher.data.localengine.LocalServerManager
import com.wludy.rolithax.launcher.data.remote.ApiClient
import com.wludy.rolithax.launcher.data.resources.CurseForgeResourceProvider
import com.wludy.rolithax.launcher.data.resources.ModrinthApiFactory
import com.wludy.rolithax.launcher.data.resources.ModrinthResourceProvider
import com.wludy.rolithax.launcher.data.resources.ResourceDatabase
import com.wludy.rolithax.launcher.data.resources.ResourceRepository
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import com.wludy.rolithax.launcher.data.localengine.LocalStorage

/** 简易手动依赖注入容器。 */
class AppContainer(context: Context) {
    /** 应用级 Context（图标缓存等非 UI 组件需要）。 */
    internal val appContext = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = settingsStore.settingsFlow.catch { error ->
        if (error !is IOException) throw error
        AppLogger.w("Settings", "启动时无法读取设置", error)
        emit(AppSettings())
    }.flowOn(Dispatchers.IO)
        .stateIn(appScope, SharingStarted.Eagerly, null)
    val localStorageReady = appScope.async { LocalStorage.migrateRootName(appContext) }

    val panelRepository by lazy { com.wludy.rolithax.launcher.data.panels.PanelRepository(appContext) }
    internal val schemaLibrary by lazy { com.wludy.rolithax.launcher.data.schema.SchemaLibrary(appContext) }

    val backupManager = BackupManager(appContext, settingsStore)

    val resourceDatabase by lazy {
        Room.databaseBuilder(appContext, ResourceDatabase::class.java, "rolithax-resources.db")
            .addMigrations(ResourceDatabase.MIGRATION_1_2)
            .build()
    }

    val resourceRepository by lazy {
        ResourceRepository(
            resourceDatabase,
            listOf(
                ModrinthResourceProvider(ModrinthApiFactory.create(appContext)),
                CurseForgeResourceProvider.create(settingsStore::readCurseForgeApiKey),
            ),
            ApiClient.buildMslServerCoreApi(),
        )
    }

    val updateRepository = UpdateRepository()

    /** 本机开服协调器：按目录名启停本机实例、发送控制台命令（委托进程级 [LocalServerRuntime]）。 */
    val localServerManager = LocalServerManager(appContext, settingsStore)

    /** 多 Daemon 同时连接管理：每台 Daemon 各持独立连接，无主从之分。 */
    val daemonRegistry = DaemonRegistry()

    /** 按 daemonId 取得目标连接（本机实例不走 REST，返回 null）。 */
    fun repositoryFor(daemonId: String): InstanceRepository? = daemonRegistry.repositoryFor(daemonId)

    /** 统一服务端目录：本机实例 + 全部已连接 Daemon 的实例。 */
    val serverCatalog = ServerCatalog(daemonRegistry) { appContext }
}

/**
 * 确保 [daemonId] 对应的连接已建立，返回该连接（未配置该服务端时返回 null）。
 *
 * 正常路径下 [DaemonRegistry.sync] 已在启动/设置变更时跑过，一次命中即返回；
 * 未命中只发生在进程重建后直接恢复二级页（如控制台）的情况，此时按设置补一次同步。
 */
suspend fun AppContainer.ensureRepository(daemonId: String): InstanceRepository? {
    daemonRegistry.repositoryFor(daemonId)?.let { return it }
    runCatching { daemonRegistry.sync(settingsStore.settingsFlow.first()) }
    return daemonRegistry.repositoryFor(daemonId)
}
