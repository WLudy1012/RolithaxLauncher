# Rolithax for Windows

Windows 客户端使用 .NET 10 和 WinUI 3，面向 x64 Windows 10 1809 及以上版本。界面沿用 Windows 系统主题、原生窗口和 Fluent 控件。Linux 继续使用 [Compose 桌面客户端](../desktop/README.md)。

## 安装

从 [GitHub Releases](https://github.com/WLudy1012/RolithaxLauncher/releases) 下载 `RolithaxLauncher-Windows-x64-Setup.exe`，按向导选择目录和快捷方式。安装包内含 .NET、Windows App SDK 和 Rolithax 原生桥接库，可离线安装。默认安装到当前 Windows 用户下，升级和卸载时保留连接配置。

## 窗口与快捷键

- 窗口使用标准云母，浅色、深色和动画跟随 Windows。高对比度与不支持云母的系统使用主题底色。
- 导航栏随窗口收窄变为图标栏，服务器筛选和操作区会重新排列。窗口使用 Per-Monitor V2 DPI 缩放。
- `Ctrl+1` 至 `Ctrl+6` 切换主页面，`Ctrl+F` 聚焦服务器搜索或原始控制台的输出筛选，`F5` 刷新概览或服务器列表。原始控制台内可用 `Ctrl+L` 清空当前显示。

## 远端管理

- 可以同时连接多个面板，并切换当前节点。连接选择器并列提供 MSLX、MCSL Future、MCSManager 10 和 Pterodactyl。第三方面板提供实例列表、原始日志、控制台命令和面板支持的电源操作。
- 支持粘贴 Rolithax 插件生成的配对码，也可以手动填写地址和 API Key。保存的配置使用当前 Windows 用户的 DPAPI 加密。
- 已保存的旧版 Compose 连接可从「连接管理」导入。旧配置中带自定义证书指纹的连接需要重新配置。默认验证 HTTPS 证书，允许明文 HTTP 或跳过证书验证时会单独确认。
- 可以搜索服务器，执行启停、重启、备份和删除，使用实时控制台、管理文件和修改实例配置。创建服务器支持普通表单及 Daemon 请求 JSON。
- 从「插件中心」浏览和管理 Git 插件源、安装 YAML/Lua 包或导入本地 ZIP。MSLX 实例可独立绑定已安装的 Schema，逐项授权控制台命令、文件操作和管理员定义的宿主机任务。Schema 3 可显示媒体、选项卡和版本检查结果，获授权的操作仍需确认。[格式与支持范围](../develop/agent/rolithax-schema.md)
- 资源页可以搜索 Modrinth 插件与模组、查看兼容版本，并跟踪或取消 Daemon 安装任务。安装需要启用 Rolithax 插件的资源扩展 API。
- 账户权限由 Daemon 决定。管理员可以添加、修改及删除账户，也可以分配当前节点的服务器权限。

## 构建

先按 [项目交付文档](../develop/agent/delivery.md) 配置 JDK、Gradle、CMake 3.22+ 和 Visual Studio C++ 工具，再使用 .NET 10 SDK 构建。Kotlin/Native DLL 提供 Daemon 客户端，`rolithax_schema.dll` 提供 C++ 解析器，两者都随应用分发。

PowerShell：

~~~powershell
.\gradlew.bat '-Prolithax.windowsNative=true' '-Pkotlin.native.distribution.downloadFromMaven=true' :shared:linkReleaseSharedMingwX64
$core = (Get-ChildItem .\shared\build\bin\mingwX64\releaseShared\*rolithax_core.dll | Select-Object -First 1).FullName
$schema = (Resolve-Path .\shared\build\schema-native\bin\rolithax_schema.dll).Path
dotnet publish .\windows\Rolithax.Windows.csproj -c Release -r win-x64 --self-contained -p:RolithaxCorePath="$core" -p:RolithaxSchemaPath="$schema" -o .\windows\build\publish
.\windows\installer\build.ps1 -AppVersion 1.8.2 -PublishDir .\windows\build\publish -OutputDir .\windows\build\installer
~~~

将 `-AppVersion` 替换为目标版本。安装器输出到 `windows/build/installer`。直接调试应用时，保留 `windows/build/publish` 下的全部文件并启动其中的 `RolithaxLauncher.exe`。
