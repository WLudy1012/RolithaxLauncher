# Linux 远端客户端

Linux 客户端使用 Compose Desktop 管理远端 MSLX Daemon。Windows 使用 [WinUI 3 客户端](../windows/README.md)。

## 当前功能

- 多节点连接与切换，概览展示当前节点、实例数量和系统监控；未获取的数据会明确标记。
- 实例搜索、筛选、排序，直接打开控制台、文件或配置，提供启停、重启、备份和删除确认。
- 控制台日志、搜索、跟随、复制，以及 Enter 发送命令和上下键浏览历史。
- 远端文件浏览、文本编辑、上传下载、新建目录、重命名与删除。打开 `server.properties` 后可切换结构化编辑；自定义属性路径需手动打开。
- 实例设置表单与 JSON 编辑、插件和模组管理、资源搜索安装、用户管理与个人资料。

真实 Daemon 和 Linux 系统密钥库仍需在对应环境验收。

## 运行与连接

从 [GitHub Releases](https://github.com/WLudy1012/RolithaxLauncher/releases) 下载 `RolithaxLauncher-Linux.tar.gz`，解压后运行 `RolithaxLauncher/bin/RolithaxLauncher`。归档内含配套 Java 运行时，请保留完整目录。开发版本可从 [桌面 CI](https://github.com/WLudy1012/RolithaxLauncher/actions/workflows/desktop.yml) 下载。

使用 Daemon 地址和 API Key 连接，默认使用 HTTPS。自签证书需要提供通过可信渠道取得的 SHA-256 指纹。只有明确开启明文连接时才允许 HTTP，适用于可信网络。

连接配置保存在系统用户配置目录，API Key 写入 Linux 系统密钥库。密钥库不可用时，界面会报告保存错误。

主题和 DPI 跟随系统，窗口初始尺寸根据屏幕工作区计算。

## 构建与测试

桌面打包使用 JDK 21。工程同时包含 Android 模块，完整构建环境还需按根 README 配置 Android SDK。

在仓库根目录执行以下命令。

```bash
./gradlew :shared:desktopTest :desktop:test
./gradlew :desktop:run
./gradlew :desktop:createDistributable
```

Linux 没有显示会话时，用 Xvfb 运行 UI 测试：

```bash
xvfb-run -a -s '-screen 0 1920x1080x24' ./gradlew :shared:desktopTest :desktop:test
```

应用输出位于 `desktop/build/compose/binaries/main/app/RolithaxLauncher`，测试报告位于 `desktop/build/reports/tests/test`。测试使用隔离配置与模拟密钥库。

## 模块边界

`shared` 负责通用模型、协议和部分远端客户端；`desktop` 负责系统窗口、界面、文件选择及凭据存储。HarmonyOS NEXT 使用单独的 ArkTS 工程和原生桥接，进度与工具链限制见 [harmony/README.md](../harmony/README.md)。
