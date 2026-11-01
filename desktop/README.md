# Windows / Linux 远端客户端

桌面端用于管理远端 MSLX Daemon，不在本机启动 Minecraft 服务端。界面参考 MSLX 的管理页面层级，使用 Compose 独立实现；没有嵌入 MSLX WebPanel 或封装其桌面代码。

## 当前功能

- 多节点连接与切换，概览展示当前节点、实例数量和系统监控；未获取的数据会明确标记。
- 实例搜索、筛选、排序，直接打开控制台、文件或配置，提供启停、重启、备份和删除确认。
- 控制台日志、搜索、跟随、复制，以及 Enter 发送命令和上下键浏览历史。
- 远端文件浏览、文本编辑、上传下载、新建目录、重命名与删除。打开 `server.properties` 后可切换结构化编辑；自定义属性路径需手动打开。
- 实例设置表单与 JSON 编辑、插件和模组管理、资源搜索安装、用户管理与个人资料。

当前桌面功能尚未与 Android 全量对齐。Windows 的本地打包、启动与界面回归已检查；真实 Daemon、Linux 桌面密钥库及跨显示器 DPI 切换仍需对应环境验收。

## 运行与连接

在成功的 [桌面 CI](https://github.com/WLudy1012/RolithaxLauncher/actions/workflows/desktop.yml) 中下载对应平台的应用工件。Windows 解压后运行 `RolithaxLauncher.exe`；Linux 解压 `tar.gz` 后运行应用目录下的 `bin/RolithaxLauncher`。保留完整目录，压缩包包含配套运行时和依赖。这些是开发构建，不是正式发布版本。

使用 Daemon 地址和 API Key 连接，默认使用 HTTPS。自签证书需要提供通过可信渠道取得的 SHA-256 指纹。只有明确开启明文连接时才允许 HTTP，适用于可信网络。

连接配置保存在系统用户配置目录，API Key 单独写入 Windows 凭据管理器或 Linux GNOME Keyring。Linux 需要可用的桌面密钥库服务和会话；密钥库不可用时会报告保存错误，不退回明文保存。

主题跟随系统；字体使用可用的 Windows 系统字体或跨平台回退。窗口初始尺寸根据屏幕工作区计算，保留系统 DPI 缩放。界面回归包含 100%、125%、150% 的渲染，以及 150% 深色模式；测试图片使用示例数据。

## 构建与测试

桌面打包使用 JDK 21。工程同时包含 Android 模块，完整构建环境还需按根 README 配置 Android SDK。Windows 打包需可被工具链发现的 WiX 3；CI 使用的 runner 和准备步骤见 `desktop.yml`。

在仓库根目录执行，Windows 将 `./gradlew` 换为 `gradlew.bat`：

```bash
./gradlew :shared:desktopTest :desktop:test
./gradlew :desktop:run
./gradlew :desktop:createDistributable
```

Linux 没有显示会话时，用 Xvfb 运行 UI 测试：

```bash
xvfb-run -a -s '-screen 0 1920x1080x24' ./gradlew :shared:desktopTest :desktop:test
```

应用输出位于 `desktop/build/compose/binaries/main/app/RolithaxLauncher`。测试报告位于 `desktop/build/reports/tests/test`，界面渲染位于 `desktop/build/visual-check`。普通测试使用隔离配置与模拟密钥库；真实 Windows 凭据库测试需显式传入 `-Prolithax.testWindowsCredentialStore=true`，不在默认 CI 中开启。

## 模块边界

`shared` 负责通用模型、协议和部分远端客户端；`desktop` 负责系统窗口、界面、文件选择及凭据存储。HarmonyOS NEXT 使用单独的 ArkTS 工程和原生桥接，进度与工具链限制见 [harmony/README.md](../harmony/README.md)。
