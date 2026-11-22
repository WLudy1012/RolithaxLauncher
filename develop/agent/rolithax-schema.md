# Rolithax Schema

Rolithax Schema 将实时日志转换为原生控件。插件使用 JSON 描述元数据，使用 XML 定义界面，通过 YAML 或受限的 Lua 从日志提取状态。用户确认后，客户端通过当前实例执行已授权的动作。

## 引擎与宿主

`native/schema` 用 C++17 解析插件，维护日志状态和证据，并校验动作与授权。解析器依赖随源码保存。

- Android 通过 JNI 传递 UTF-8 字节数组，使用现有 NDK 构建。实例退出时销毁会话。
- Windows 的 Kotlin/Native 核心通过 C ABI 调用同目录的 `rolithax_schema.dll`；WinUI 3 负责布局、输入和命令确认。
- JVM 桌面使用 JNA。Linux 分发包包含原生库；该宿主的界面能力仍以其已有实现为准。

Android 和 Windows 支持 SDK 1–3。SDK 2 增加指标、进度、徽标、多参数命令和 Lua。SDK 3 增加图片、视频、选项卡、折叠组、开关和选项列表，以及文件和宿主机任务声明。控件、参数和限额见 [原生协议](../../native/schema/contract.md)。

Lua 回调可根据日志、版本检查和动作结果更新面板字段。脚本的文件、网络和进程访问受限，回调有内存和执行预算。失败的热加载保留正在使用的插件。包切换后须重新确认待执行动作。

## 插件中心

Android 从「概览 → Schema 插件」进入，Windows 从导航栏的「插件中心」进入。可以查看已安装的插件、浏览目录、添加自定义源或导入本地 ZIP。安装后打开实例控制台的「Rolithax Schema」，选择插件并逐项授权。绑定按实例保存，更新包后需要重新授权。

默认源是 [rolithax-schema-plugins](https://github.com/WLudy1012/rolithax-schema-plugins)。自定义源接受公开的 GitHub 或 GitLab 仓库主页，以及可直接下载的 HTTPS `README.md`。GitHub 和 GitLab 的目录及仓库内文件按固定提交读取。自托管目录按声明的大小和摘要校验。

目录 V2 使用 `rolithax-schema-repository-v2` 的 JSON 代码块和 `schemaVersion: 2`，要求仓库与插件条目都带作者、主页和图标。插件条目还需声明许可证和 SDK 版本。图标描述包含格式、字节数、SHA-256，以及仓库内相对路径或 HTTPS 地址。兼容旧客户端时，同一 README 可保留 SDK 1、2 使用的 `rolithax-schema-repository` 代码块。

- YAML 可在 `source.type=files` 下列出三个定义文件和包内媒体。客户端验证每个文件后生成 ZIP。也可直接发布完整 ZIP。
- Lua 使用 `source.type=package` 发布预构建 ZIP，提供 HTTPS 下载地址、大小和 SHA-256。包内包含 `business.lua` 源码。
- 安装一次下载完整插件，包含清单声明的 HTTPS 外链媒体。已校验的媒体保存在本地。支持 PNG、JPEG、WebP 和 MP4，单张图片限 8 MiB，单个视频限 64 MiB，SDK 3 的媒体总量和包大小各限 128 MiB。

## 实例权限与版本检查

声明 `preflight` 的插件在启用时检查版本。MSLX 扩展读取 Minecraft 插件、模组或 MCDR 插件的文件元数据，并按清单要求比较精确版本。缺少组件或检测到不同版本时阻止启用。无法确认版本时，用户可选择继续或取消。

元数据无法确认版本时，如果管理员已为该组件和实例登记只读版本查询，客户端可请求运行该预设。每次查询都要先确认。查询仍无法确认版本时，启用前仍需用户明确同意。文件元数据反映磁盘上的组件版本。

打开已绑定的实例时会再次检查元数据。Windows 如果需要确认，先显示内置面板，可从版本检查提示重新启用原插件。

控制台命令、文件动作和宿主机任务分别按动作授权，执行前再确认目标。文件动作通过 Daemon 的实例文件 API，按清单指定的目录定位。MCDR 适配用于 MSLX 管理的实例，支持 MCDR 配置目录、插件目录及 Minecraft 工作目录。MSLX 扩展提供目录映射。第三方面板提供原始控制台和电源控制。 [面板支持范围](remote-panels.md)

宿主机任务需要在 Daemon 侧部署 Worker，并由管理员在 Rolithax 中心启用。Worker 按任务 ID、用户和实例核对权限，用独立的低权限账户执行预设程序和固定参数。部署步骤见 MSLX 扩展仓库的 `Rolithax.Schema.Worker/README.md`。 [Rolithax MSLX 扩展](https://github.com/WLudy1012/Rolithax-MSLX-plugin)

## SDK 和模板

SDK CLI 提供 `init` 创建项目、`check` 校验源码或 ZIP、`replay` 回放样本、`pack` 打包，以及 `source-index` 校验仓库目录。校验和回放使用客户端同一套 C++ 引擎。SDK 仓库包含插件开发 Agent skill。 [SDK](https://github.com/WLudy1012/rolithax-console-sdk) [模板](https://github.com/WLudy1012/rolithax-console-plugin-template)

## 验证与兼容范围

Paper、LuckPerms 5.4/5.5 和 Chunky 1.4/1.5 的解析覆盖对应日志样本。Chunky 的暂停和继续按世界执行，进度查询读取上游返回的任务。

本地检查包括 CTest、共享解析回归、SDK YAML/Lua 回放、Android APK 和 Windows C ABI。界面使用本机模拟 Daemon 验收。真实 MCDR 部署和独立 Worker 账户仍需实机验收。

构建入口：`:shared:buildSchemaNative`、`:shared:testSchemaNative`、`:shared:desktopTest`。Windows 发布必须同时传入 `RolithaxCorePath` 和 `RolithaxSchemaPath`。Android 与桌面 CI 检查原生库存在，不能只打包宿主代码。
