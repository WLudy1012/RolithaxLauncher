# 分层架构与模块职责

## 总体结构

项目是单模块 Android 应用，采用 MVVM、手动依赖注入和 Compose 状态驱动界面：

```text
Compose Screen
    ↓ 用户意图 / immutable UI state
ViewModel
    ↓ 协程、StateFlow、业务编排
Repository / Manager
    ↓ REST、SignalR、Room、文件系统、Native
Daemon / MSLAPI / 本地 JVM / Android 系统
```

界面只负责渲染状态和发送事件。网络、文件、数据库、JVM 和权限操作由 ViewModel 下方的仓库或协调器拥有，避免在 Composable 中直接做不可控的副作用。

## 应用容器

`RolithaxApplication` 创建并持有 `AppContainer`。容器集中提供：

- `SettingsStore`：DataStore 设置和多 Daemon 配置。
- 主 `InstanceRepository`：默认 Daemon 的 REST 与 SignalR 资源。
- `DaemonRegistry`：多 Daemon 仓储、探测、延迟和主连接切换。
- `ServerCatalog`：本机实例和所有 Daemon 实例的统一目录。
- `ResourceRepository`：资源 Provider、Room 数据库和资源安装协作。
- `LocalServerManager`：本机实例启动、停止、重启和引擎选择。
- `UpdateRepository`：版本查询、渠道过滤和更新资产选择。

ViewModel 通过 `AndroidViewModel` 获取 Application，再从容器取得仓库；不在 ViewModel 中重复创建 OkHttp、数据库或 SignalR 客户端。

## 数据层

### 远程实例

`InstanceRepository` 封装 Daemon 的实例、文件、用户、Java、创建、插件模组、备份和状态接口，同时跟踪与实例相关的 SignalR 客户端。连接配置变化、Daemon 删除或仓储不再需要时必须释放连接池、Dispatcher、缓存和 Hub 资源。

### 多 Daemon

`DaemonRegistry` 为每台 Daemon 保持独立配置和探测状态。主连接可以复用应用已有的全局仓储，其他连接使用独立仓储；探测采用结构化并发，失败只影响对应 Daemon。`ServerCatalog` 将本机实例与每台 Daemon 的实例映射成带 `ServerRef` 的统一 `ManagedServer`，后续操作必须依据引用中的来源和实例 ID 寻址，不能只按显示名称猜测目标。

### 本机资源

`LocalStorage` 负责本机运行时、实例目录和下载缓存；`LocalInstanceStore` 负责扫描、加载、分配目录名和删除；`ServerFiles` 负责幂等补全 EULA、配置、元数据和核心目录。所有路径操作都需要保持在允许的实例根目录内，并拒绝路径穿越。

### 资源中心

`ResourceProvider` 抽象资源来源，`ModrinthResourceProvider` 实现 Modrinth v3，MSLAPI 服务端核心由独立接口提供。`ResourceRepository` 负责 Provider 调用、Room 缓存、Paging/RemoteMediator、ETag 增量刷新、版本缓存、依赖关系和安装前校验。

## UI 与导航

- 单一 `AppNavHost` 管理路由；主页、实例、新建、资源和设置构成顶层 Dock。
- Dock 位于 NavHost 外部，避免页面切换时重复创建和重叠；顶层页面使用单例导航语义。
- 窗口宽度达到 600dp 且高度达到 360dp 时使用左侧 `NavigationRail`；更紧凑的窗口保留底部 Dock。导航轨高度不足 480dp 时收起未选中标签，避免横屏拥挤。
- 远程控制台由 `ConsoleViewModel` 实现 `ConsoleController`，本地控制台由 `LocalConsoleViewModel` 实现同一接口，`ConsoleScreen` 只依赖控制器契约。
- 状态由 ViewModel 的 `StateFlow` 暴露，Compose 使用生命周期感知的收集方式；一次性提示使用明确的事件或状态清理逻辑。
- 所有加载、刷新、重试、错误、空态和缓存态都要有可见状态，不能把网络失败静默显示成“没有数据”。
- 毛玻璃由 `ui/theme/Glass.kt` 统一提供：`MainActivity` 的 `GlassHost` 绘制背景（自定义图或主题色柔光）并注册为 Haze 模糊源；`MslxCard`、`GlassSurface`、Dock 与导航轨通过 `rememberGlassStyle` + `Modifier.glass` 绘制面板。仅在设置了背景图、设备支持（Haze 默认 API 32+）且不透明度 < 100% 时启用实时模糊，其余情况降级为半透明表面色并抬高不透明度下限保证对比度。页面 Scaffold 保持透明；弹窗位于独立窗口，内部不使用玻璃面板。

## 协程与并发约束

- 每个异步任务必须有生命周期所有者，通常是对应 ViewModel 的 `viewModelScope` 或仓库提供的可取消挂起函数。
- IO 和文件系统操作放在 IO 调度器；取消异常必须继续向上传播，不能被普通异常兜底吞掉。
- 快速切换项目、实例或 Daemon 时，取消旧请求或使用请求代际校验，旧响应不能覆盖当前选择。
- `StateFlow` 保存当前可渲染状态；临时提示、导航或重试事件不能通过容易丢失的隐式回调传递。
- 轮询循环和 DataStore 读写要有异常记录和取消处理，`Result` 返回值必须被显式消费。

## Native 与 JNI

进程内 JVM 桥接代码位于 `app/src/main/cpp`，Kotlin 的 `NativeVm` 位于 `com.wludy.rolithax.launcher.localengine`。JNI 导出符号必须与当前 Kotlin 包名和类名一致；修改包结构时要同步检查 C 导出函数或改用显式 `RegisterNatives`。JNI 调用前后的 fd、线程和 JVM 生命周期都要有失败清理路径。
