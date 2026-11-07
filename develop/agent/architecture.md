# 分层架构与模块职责

## 总体结构

`app` 是 Android 宿主，保留本机开服、权限和 JNI。`shared` 提供可复用的模型、远端 Daemon REST 和 SignalR 客户端。`windows` 是 WinUI 3 宿主，`desktop` 提供 Linux 的 Compose 客户端，`harmony` 是独立的 HarmonyOS NEXT 原生工程。

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
- Windows 和 Linux 客户端面向远端 Daemon。Windows 使用 DPAPI，Linux 使用系统密钥库。本机 JVM、Shizuku 和 Android 前台服务由 `app` 管理。
- Windows 工作区使用 .NET 10、WinUI 3、系统主题和 Per-Monitor V2 DPI。页面层级参考 MSLX Desktop 与 WebPanel，界面由本项目独立实现。宿主通过 C ABI 调用 `shared` 的 Kotlin/Native DLL，按 Daemon 打开独立会话。
- Linux 使用 Compose Desktop，Windows CI 的应用产物使用 WinUI 3。
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
- `DaemonRegistry` 按 Daemon ID 管理独立的 REST 和 SignalR 仓储、探测状态及可用地址。
- `ServerCatalog`：本机实例和所有 Daemon 实例的统一目录。
- `ResourceRepository`：资源 Provider、Room 数据库和资源安装协作。
- `LocalServerManager`：本机实例启动、停止、重启和引擎选择。
- `UpdateRepository`：版本查询、渠道过滤和更新资产选择。

ViewModel 通过 `AndroidViewModel` 获取 Application，再从容器取得仓库；不在 ViewModel 中重复创建 OkHttp、数据库或 SignalR 客户端。

## 数据层

### 远程实例

`InstanceRepository` 封装 Daemon 的实例、文件、用户、Java、创建、插件模组、备份和状态接口，同时跟踪与实例相关的 SignalR 客户端。连接配置变化、Daemon 删除或仓储不再需要时必须释放连接池、Dispatcher、缓存和 Hub 资源。

### 多 Daemon

`DaemonRegistry` 为每台 Daemon 保持独立配置和探测状态。默认 Daemon 用于概览的初始选择和新建实例的默认目标。`ServerCatalog` 并行查询本机与各 Daemon，返回已加载实例和按 Daemon 分组的错误。操作按 `ServerRef` 中的来源和实例 ID 寻址，本机命令按目录绑定正在运行的引擎。

### 本机资源

`LocalStorage` 负责本机运行时、实例目录和下载缓存；`LocalInstanceStore` 负责扫描、加载、分配目录名和删除；`ServerFiles` 负责幂等补全 EULA、配置、元数据和核心目录。所有路径操作都需要保持在允许的实例根目录内，并拒绝路径穿越。

### 资源中心

`ResourceProvider` 抽象资源来源，`ModrinthResourceProvider` 实现 Modrinth v3，MSLAPI 服务端核心由独立接口提供。`ResourceRepository` 负责 Provider 调用、Room 缓存、Paging/RemoteMediator、ETag 增量刷新、版本缓存、依赖关系和安装前校验。

## UI 与导航

- `AppNavHost` 管理概览、实例、连接、资源和设置。新建入口位于概览与实例页，可按实例页的当前来源预选本机或 Daemon。
- 紧凑窗口使用 Material 3 底部导航栏，宽屏窗口使用导航轨。实例可按来源和状态筛选，批量停止沿用已确认的列表范围。
- 远程控制台由 `ConsoleViewModel` 实现 `ConsoleController`，本地控制台由 `LocalConsoleViewModel` 实现同一接口，`ConsoleScreen` 只依赖控制器契约。远端日志接入共享 SignalR 控制台传输。
- Android 和 WinUI 3 按实例会话持有 `ConsolePluginWorkspace`。共享代码将 JSON、XML 与 YAML 编译为面板控件树、日志规则和命令声明，宿主用原生控件渲染。Windows 经 C ABI 轮询日志及面板增量，独立检查流代际和插件版本。导入候选包时先完成校验和回放，通过后替换当前面板。
- 状态由 ViewModel 的 `StateFlow` 暴露，Compose 使用生命周期感知的收集方式；一次性提示使用明确的事件或状态清理逻辑。
- 所有加载、刷新、重试、错误、空态和缓存态都要有可见状态，不能把网络失败静默显示成“没有数据”。
- `ui/theme/Glass.kt` 默认使用主题表面色。用户设置背景图后，`GlassHost` 将背景注册为 Haze 模糊源，支持的设备按透明度设置绘制玻璃面板。底栏和导航轨使用 Material 3 表面容器色，透明 Scaffold 继承主题的正文色。

## 协程与并发约束

- 每个异步任务必须有生命周期所有者，通常是对应 ViewModel 的 `viewModelScope` 或仓库提供的可取消挂起函数。
- IO 和文件系统操作放在 IO 调度器；取消异常必须继续向上传播，不能被普通异常兜底吞掉。
- 快速切换项目、实例或 Daemon 时，取消旧请求或使用请求代际校验，旧响应不能覆盖当前选择。
- `StateFlow` 保存当前可渲染状态；临时提示、导航或重试事件不能通过容易丢失的隐式回调传递。
- 轮询循环和 DataStore 读写要有异常记录和取消处理，`Result` 返回值必须被显式消费。

## Native 与 JNI

进程内 JVM 桥接代码位于 `app/src/main/cpp`，Kotlin 的 `NativeVm` 位于 `com.wludy.rolithax.launcher.localengine`。JNI 导出符号必须与当前 Kotlin 包名和类名一致；修改包结构时要同步检查 C 导出函数或改用显式 `RegisterNatives`。JNI 调用前后的 fd、线程和 JVM 生命周期都要有失败清理路径。
