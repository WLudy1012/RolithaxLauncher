# Rolithax Schema

Schema 将实时日志转换为原生控件。用户在面板填写参数、确认具体命令后，宿主通过当前实例连接发送命令。解析器不会主动连接服务器，也不会根据日志自动执行操作。

## 引擎与宿主

`native/schema` 是唯一的业务解析实现：C++17 负责 JSON 元数据、XML 控件、YAML 规则、Lua 脚本、日志状态、证据和命令授权。依赖固定版本随源码保存，构建不下载解析器依赖。

- Android 通过 JNI 传递 UTF-8 字节数组，使用现有 NDK 构建。实例退出时销毁会话。
- Windows 的 Kotlin/Native 核心通过 C ABI 调用同目录的 `rolithax_schema.dll`；WinUI 3 负责布局、输入和命令确认。
- JVM 桌面使用 JNA。Linux 分发包包含原生库；该宿主的界面能力仍以其已有实现为准。

协议和限额见 [`native/schema/contract.md`](../../native/schema/contract.md)。既有 SDK 1 包保持兼容；SDK 2 增加 `row`、`metric`、`progress`、`badge`、`divider`，支持多参数动作和 Lua。进度是 0–100 的百分比，缺少日志时显示等待状态。

Lua 运行时移除文件、网络、进程、模块及动态加载接口，每次调用限制内存和指令数。Lua 返回字段更新，命令仍由宿主授权。脚本失败保留上次快照，并显示错误或日志缺失状态；失败的热加载不会替换当前插件。每次替换或重置更新包修订号，旧确认不能发送新包的命令。

## Git 插件源

默认源：[rolithax-schema-plugins](https://github.com/WLudy1012/rolithax-schema-plugins)。可添加 GitHub、GitLab 仓库主页，也可提供自托管的 HTTPS 原始 `README.md` 地址。当前不支持私有仓库凭据或直接克隆任意 Git 协议。

README 包含且仅包含一个 `rolithax-schema-repository` JSON 代码块，`schemaVersion` 为 1。GitHub/GitLab 先解析提交再读取固定提交的 README 和文件。GitHub 匿名 API 返回 403/429 时使用 HEAD Atom 提交源取得固定 SHA。自托管 README 无 Git 提交发现，仍须校验其声明的每个文件摘要。

- YAML 的 `source.type=files`：列出 `plugin.json`、`ui.xml`、`rules.yaml` 的相对路径、字节数和 SHA-256。客户端校验后在本地生成 ZIP。
- `source.type=package`：提供 HTTPS ZIP 下载地址、大小和 SHA-256。Lua 只能按此方式分发，包内是 `business.lua` 源码及元数据，不是本地 DLL 或 Lua 字节码。
- README 限 256 KiB，ZIP 限 512 KiB；禁止目录穿越、重复条目、未知文件及跨引擎混入业务。文件分别受限，解压不会写出包内路径。
- 源地址与已下载包保存在应用私有目录；安装后仍进入权限选择。下载、校验和解析不等于获得命令权限。

正式协议由[独立 SDK 仓库](https://github.com/WLudy1012/rolithax-console-sdk)维护，模板位于[独立模板仓库](https://github.com/WLudy1012/rolithax-console-plugin-template)。SDK 的校验和回放直接调用同一原生 CLI，不维护第二套规则解析器。

## 原生工作区

Android 与 Windows 采用用户选定的任务优先布局：实例状态和启停操作位于顶部，Schema 与原始日志明确切换；解析状态、输入和相关动作分节排列，日志依据可展开。宽窗口允许分节并排，窄窗口按阅读顺序折行。Android 跟随 Material 3 和动态色，Windows 使用 Fluent 原生控件及宿主云母背景。

面板显示由插件 XML 和日志决定。设计图中的世界名、进度等只用于布局示例，不得写成生产默认值。原始日志、手动命令、已有文件与配置入口继续由各宿主提供。

## 验证与兼容范围

官方源提供 Paper、LuckPerms 5.4/5.5、Chunky 1.4/1.5。版本覆盖基于上游格式核对和对应日志样本回放；不要写成所有 Minecraft 平台、语言及每个补丁版本都经过真服测试。Chunky 的暂停和继续指定世界，查询进度遵循上游返回全部任务的行为。

本地检查包括 CTest、共享解析回归、Android 包和源边界测试、SDK YAML/Lua 回放、Windows C ABI 冒烟测试。UI 验收使用本机模拟 Daemon，不对真实服务器发送命令。

构建入口：`:shared:buildSchemaNative`、`:shared:testSchemaNative`、`:shared:desktopTest`。Windows 发布必须同时传入 `RolithaxCorePath` 和 `RolithaxSchemaPath`。Android 与桌面 CI 检查原生库存在，不能只打包宿主代码。
