# 分层架构与模块职责

## 总体结构

项目正在从单模块 Android 应用迁移到 Kotlin Multiplatform。`app` 仍是 Android 宿主，保留本机开服、权限和 JNI；`shared` 提供 Android 与桌面可复用的模型和远端 Daemon 客户端；`desktop` 是 Windows/Linux 桌面宿主。`harmony` 是独立的 HarmonyOS NEXT 原生工程，共享 Kotlin 核心的鸿蒙编译与桥接尚待验证。桌面和鸿蒙的远端功能仍在迁移中，不能视为已与 Android 对齐。

Android 应用采用 MVVM、手动依赖注入和 Compose 状态驱动界面：

```text
Compose Screen
    ↓ 用户意图 / immutable UI state
ViewModel
    ↓ 协程、StateFlow、业务编排
Repository / Manager
    ↓ REST、SignalR、Room、文件系统、Native
Daemon / MSLAPI / 本地 JVM / Android 系统
```

## 多平台迁移边界

- `shared` 的通用源码包含远端 Daemon 协议、SignalR、资源规则和公共模型；Android 与桌面宿主分别注入网络和系统能力。Android 的迁移按接口逐步进行，未迁移的调用仍使用现有 Retrofit 仓库。
- `desktop` 面向 Windows 和 Linux 管理远端 Daemon，连接凭据使用系统密钥存储。本机 JVM、Shizuku 和 Android 前台服务继续由 `app` 管理。
- Windows 工作区采用系统窗口与 Fluent 风格的浅灰、蓝色及深色主题。页面层级参考 [MSLX Desktop](https://github.com/MSLTeam/MSLX/tree/dev/MSLX.Desktop) 和 [WebPanel](https://github.com/MSLTeam/MSLX/tree/dev/MSLX.WebPanel)，由本项目使用 Compose 独立实现：大品牌侧栏提供概览、实例管理、新建、用户与连接入口；首页展示真实的节点、实例数量和 CPU/内存监控状态，不补造未提供的信息。
- 实例列表支持按名称、ID、核心搜索，按状态筛选和按名称或 ID 排序；全宽卡片直接提供控制台、启停、文件和设置操作，详情工作区顶部显示实例状态，并以页签切换功能。界面保留适合 150% 显示缩放的字号、布局和滚动，遵循系统 DPI；多显示器 DPI 切换需在目标环境验证。
- 桌面控制台支持 Enter 发送、上下键浏览命令历史、日志搜索、跟随输出和复制。文件或实例配置有未保存修改时，工作区导航会请求确认；具体设计约定见 [`desktop/DESIGN.md`](../../desktop/DESIGN.md)。
- 桌面文件管理器打开文件名为 `server.properties` 的文件时提供结构化和文本两种编辑模式，结构化模式复用 Android 的属性字段定义，并通过现有文件保存接口提交；它只展示文件中已有的已知、非续行字段。未知字段、注释和文件换行保留在文本中；新增字段、续行及自定义文件名需使用文本模式，当前不会从实例设置自动定位自定义路径。
- 桌面远端文件管理通过 Daemon 接口创建目录、重命名及删除条目；删除目录是递归操作。Daemon 删除接口可能在 HTTP 成功时报告部分失败，客户端保留其结果并刷新列表，不以 HTTP 状态推断全部成功。
- 桌面远端实例设置同时提供结构化表单与 JSON 编辑；提交前校验非空内存值为非负整数，且最大内存不小于最小内存，两种编辑方式共用同一校验。
- `harmony/kmp-domain` 使用 Kuikly Kotlin/Native 编译选定的共享源码，通过 N-API 提供给鸿蒙原生入口。鸿蒙网络目前由 ArkTS 实现：现有 Ktor 依赖没有 OHOS 变体，不能将领域库链接成功当作共享网络层已迁移。
- 多平台构建应分别验证 Android 测试与 APK、共享测试、桌面测试和目标平台包；鸿蒙 HAP、真实设备、真实 Daemon、Linux 系统密钥库和桌面端平台交互均需要对应环境验收。构建命令和鸿蒙前提见 [`harmony/README.md`](../../harmony/README.md)。

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
