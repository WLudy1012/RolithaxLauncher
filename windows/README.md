# Rolithax for Windows

Windows 客户端使用 .NET 10 和 WinUI 3，面向 x64 Windows 10 1809 及以上版本。界面沿用 Windows 系统主题、原生窗口和 Fluent 控件。Linux 继续使用 [Compose 桌面客户端](../desktop/README.md)。

## 安装

从 [GitHub Releases](https://github.com/WLudy1012/RolithaxLauncher/releases) 下载 `RolithaxLauncher-Windows-x64-Setup.exe`，按向导选择目录和快捷方式。安装包内含 .NET、Windows App SDK 和 Rolithax 原生桥接库，可离线安装。默认安装到当前 Windows 用户下，升级和卸载时保留连接与外观配置。

## 窗口与快捷键

- 在「设置 → 外观」选择跟随系统、浅色或深色，以及云母、云母 Alt 或纯色。选择会在下次启动时恢复。在不支持云母的系统上，窗口使用纯色。
- 导航栏随窗口收窄变为图标栏，服务器筛选和操作区会重新排列。窗口使用 Per-Monitor V2 DPI 缩放。
- `Ctrl+1` 至 `Ctrl+5` 切换主页面，`Ctrl+F` 聚焦服务器搜索或原始控制台的输出筛选，`F5` 刷新概览或服务器列表。原始控制台内可用 `Ctrl+L` 清空当前显示。

## 远端管理

- 可以同时连接多个 Daemon，并切换当前节点。服务器、文件、控制台和账户操作都指向当前节点。
- 支持粘贴 Rolithax 插件生成的配对码，也可以手动填写地址和 API Key。保存的配置使用当前 Windows 用户的 DPAPI 加密。
- 已保存的旧版 Compose 连接可从「连接管理」导入。旧配置中带自定义证书指纹的连接需要重新配置。默认验证 HTTPS 证书，允许明文 HTTP 或跳过证书验证时会单独确认。
- 可以搜索服务器，执行启停、重启、备份和删除，使用实时控制台、管理文件和修改实例配置。创建服务器支持普通表单及 Daemon 请求 JSON。
- 打开服务器控制台时进入「面板」，可随时切换到共用连接的「原始控制台」。内置 Paper 面板从日志提取服务器状态和玩家信息，并提供日志依据。也可以在当前会话导入由 `plugin.json`、`ui.xml`、`rules.yaml` 组成的 ZIP。第三方命令默认禁用，授予权限后仍需逐次确认。
- 资源页可以搜索 Modrinth 插件与模组、查看兼容版本，并跟踪或取消 Daemon 安装任务。安装需要启用 Rolithax 插件的资源扩展 API。
- 账户权限由 Daemon 决定。管理员可以添加、修改及删除账户，也可以分配当前节点的服务器权限。

## 构建

先按 [项目交付文档](../develop/agent/delivery.md) 配置 JDK 和 Gradle，再使用 .NET 10 SDK 构建。Kotlin/Native DLL 通过 C ABI 提供 Daemon 客户端，由 WinUI 宿主加载。

PowerShell：

~~~powershell
.\gradlew.bat '-Prolithax.windowsNative=true' '-Pkotlin.native.distribution.downloadFromMaven=true' :shared:linkReleaseSharedMingwX64
$core = (Get-ChildItem .\shared\build\bin\mingwX64\releaseShared\*rolithax_core.dll | Select-Object -First 1).FullName
dotnet publish .\windows\Rolithax.Windows.csproj -c Release -r win-x64 --self-contained -p:RolithaxCorePath="$core" -o .\windows\build\publish
.\windows\installer\build.ps1 -AppVersion 1.8.2 -PublishDir .\windows\build\publish -OutputDir .\windows\build\installer
~~~

将 `-AppVersion` 替换为目标版本。安装器输出到 `windows/build/installer`。直接调试应用时，保留 `windows/build/publish` 下的全部文件并启动其中的 `RolithaxLauncher.exe`。
