<div align="center">

<img src="./app/src/main/res/drawable-nodpi/rolithax_logo.png" alt="Rolithax Launcher" width="96">

# Rolithax Launcher

**一个 App，同时管理远程 MSLX Daemon 和手机本机上的 Minecraft Java 服务端。**

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Android CI](https://github.com/WLudy1012/RolithaxLauncher/actions/workflows/android.yml/badge.svg)](https://github.com/WLudy1012/RolithaxLauncher/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/WLudy1012/RolithaxLauncher?include_prereleases&label=release)](https://github.com/WLudy1012/RolithaxLauncher/releases)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84)

**简体中文** · [繁體中文](README.zh-TW.md) · [English](README.en.md)

</div>

Rolithax Launcher 是 [MSLX Daemon](https://github.com/MSLTeam/MSLX) 的**第三方** Android 客户端，基于 Kotlin 与 Jetpack Compose 构建。
它把「连接远程 Daemon 管理实例」和「直接在手机上开服」放进同一套实例列表与控制台，并针对触屏重新设计了操作流程。

> 本应用与 MSLTeam 没有任何隶属或关联关系，使用前请阅读[免责声明](#免责声明)。
> 包名 `com.wludy.rolithax.launcher`。更换包名与签名后，旧版需全新安装，旧数据通过应用内加密导出/导入迁移。

本应用集成 MSLAPI 下载服务：**本服务由MSL开服器提供**。<a href="https://mslmc.cn"><img src="./app/src/main/res/drawable-nodpi/msl_logo.png" alt="MSL 开服器 Logo" width="32"></a> [mslmc.cn](https://mslmc.cn)

## 下载

在 [GitHub Releases](https://github.com/WLudy1012/RolithaxLauncher/releases) 或 [CNB 镜像](https://cnb.cool/WLudy/RolithaxLauncher/-/releases)（大陆网络更快）下载，每个版本提供两个包：

| 包 | 说明 |
| --- | --- |
| `app-release.apk` 完整版 | 内嵌 Java 17 运行时，本机开服可离线使用 |
| `app-release-lite.apk` 精简版 | 体积更小，首次本机开服需联网下载约 36 MB 运行时 |

两个包签名一致，可互相覆盖安装。应用内也可直接检查并安装更新，见[更新与版本](#更新与版本)。

## 功能

**连接与管理**
- 同时保存多台 Daemon，所有入口按**实例**寻址，本机实例与各 Daemon 的实例合并在同一个列表里。
- 扫描 Daemon 页面生成的二维码即可配对；配对依赖本项目的 [Daemon 插件](https://github.com/WLudy1012/Rolithax-MSLX-plugin)，也可以手动填写地址与 API Key。
- 启动、停止、重启、强制结束、备份；未同意 EULA 时自动引导。实例可置顶，删除需二次确认。

**控制台**
- SignalR 实时日志，增量解析 ANSI 颜色与样式；发送命令、自动滚动、一键回到最新。
- 远程与本机实例共用同一套控制台界面。

**创建与资源中心**
- 创建向导覆盖基本信息、服务端核心、Java 环境、资源配置与确认，支持在线核心库、远程下载、本地上传和整合包。
- 资源中心：浏览 Modrinth 的模组、插件、整合包与数据包，按目标实例的核心与游戏版本筛选；统一检查依赖与冲突后一次安装。
- 目标是远程 Daemon 时，由 Daemon 所在机器直接下载安装，手机不做中转。

**本机开服**
- 不依赖 Daemon、Termux 或 proot，在手机上直接运行 Minecraft Java 服务端，详见[本机开服](#本机开服)。

**设置与安全**
- 文件管理、插件与模组管理、`server.properties` 编辑、加密备份与迁移。
- 毛玻璃界面、动态取色与自定义背景；暗色主题；横屏与平板使用侧边导航。
- 运行日志可查看、复制、导出，自动脱敏；崩溃后可一键提交 Issue。

## 快速开始

**连接远程 Daemon**

1. 在电脑上启动 MSLX Daemon，准备好它的地址与 API Key。
2. 打开 App，在「连接守护程序」页填写地址（例如 `https://192.168.1.100:1027`）和 API Key，点击连接。
   安装了 Rolithax 插件的话，也可以直接扫描插件页面的二维码。
3. 进入实例列表，点任意实例即可查看日志、发送命令、启停。

**直接在手机上开服**

1. 点底部 Dock 中央的「+」，目标选「本机」。
2. 选择服务端核心与版本，完成向导；首次使用会按需安装 Java 运行时。
3. 启动实例，在控制台里和平常一样操作。

## 本机开服

实例目录有两种放法，目录名在两个位置之间全局唯一：

- **公共目录**（默认）：`/storage/emulated/0/MSLX/servers/<实例名>`，可用文件管理器直接放核心、模组和备份。需要 Android 11+ 的「所有文件访问」权限；未授权时不会阻断开服，会自动改建到私有目录并提示。
- **应用私有目录**：`files/mslx/servers/<实例名>`，无需授权，只能通过 App 内的文件管理读写。

两种引擎：

| 引擎 | 原理 | 限制 |
| --- | --- | --- |
| 基线（进程内 JVM） | NDK `dlopen libjvm.so` 并创建 JVM，无需任何授权 | 一个进程只能创建一个 JVM，停止后需完全退出 App 才能再启动 |
| 增强（Shizuku） | 以 shell 权限启动真正的 `java` 子进程 | 需要 Shizuku 授权；支持多实例并发、原地重启与跨 Java 版本，未授权时自动回退到基线 |

Java 运行时可在设置里安装多个并按实例选择，启动前会做版本守卫（运行时低于核心要求时直接拦下并给出建议）：

| 游戏版本 | 建议运行时 |
| --- | --- |
| 1.16 及以下 | Java 8 |
| 1.17 – 1.20.4 | Java 17（完整版内嵌） |
| 1.20.5+ | Java 21 |
| 26.x+ | Java 25 |

所有运行时均校验 SHA-256，下载源按「CNB 镜像 → GitHub Release → 上游」依次回退，缺少可信摘要时失败关闭。运行中由前台服务与常驻通知保活，通知栏可直接停止。

## 安全

- 默认强制 HTTPS：未带协议的地址自动补 `https://`，明文 `http://` 默认升级为 HTTPS；只有在连接页明确允许并确认风险后才保留明文，且仅限可信内网。
- 自签证书只对你自己配置的 Daemon 生效；MSLAPI、GitHub 等公共服务仍使用系统信任链。
- API Key 使用 Android Keystore AES-GCM 加密存储，不随系统备份；应用内导出/导入使用独立的口令加密归档，不导出 Keystore 密钥。
- 下载一律流式处理并校验摘要，解压限制路径、单文件大小、总大小与条目数；运行中的实例禁止删除或迁移。
- 日志只记录脱敏后的信息，不含地址、路径、Token 与 API Key。

## 更新与版本

- **稳定版**：tag 无后缀（如 `v1.8.0`）。**测试版**：tag 带 `-Beta`。**强制更新**：tag 带 `-Force`，客户端不可跳过。
- 设置页的「更新渠道」默认为稳定版；测试渠道同时接收稳定版与 Beta。更新在应用内下载并校验后交给系统安装器。
- 更新优先从 CNB 镜像下载，失败时回退 GitHub Releases。
- 版本按数字逐段比较，正式版的版本号必须高于已发布的同号 Beta，否则 Beta 用户不会收到它。

## 构建

要求：JDK 17+、Android SDK Platform 35、NDK 28.2.13676358。

```bash
./gradlew :app:compileDebugKotlin     # 编译检查
./gradlew :app:testDebugUnitTest      # 单元测试
./gradlew assembleDebug               # Debug APK
./gradlew assembleRelease             # 签名 Release，需要 keystore.properties
./gradlew assembleRelease -PwithoutJre=true   # 精简版，不内嵌 JRE
```

首次同步需要联网。国内网络可设置 `MSLX_MAVEN_GOOGLE_MIRROR` 与 `MSLX_MAVEN_CENTRAL_MIRROR` 使用镜像仓库。
内嵌 JRE 用 `fetch-jre-assets.ps1` 获取，`LD_PRELOAD` 垫片用 `build-shim.ps1` 重编。

## 项目结构

```text
app/src/main/java/com/wludy/rolithax/launcher/
├── RolithaxApplication.kt   Application 与依赖容器
├── MainActivity.kt          单 Activity、免责门控、更新与崩溃入口
├── localengine/             NativeVm 与 JNI 声明
├── data/
│   ├── remote/              Retrofit、SignalR、网络客户端
│   ├── localengine/         本机实例、JRE、引擎、保活
│   ├── resources/           资源 Provider、Room、Paging、安装
│   └── ...                  仓库、设置、加密、备份、日志
└── ui/                      home / instances / console / create / servers / settings / ...
app/src/main/cpp/            进程内 JVM 的 Native 桥接
```

架构、接口契约、安全约束与发布流程见 [`develop/agent/`](develop/agent/README.md)。

## 相关仓库

- [Rolithax-MSLX-plugin](https://github.com/WLudy1012/Rolithax-MSLX-plugin)：Daemon 插件，提供扫码配对、实例图标与资源远端直装。
- [RolithaxLauncher-website](https://github.com/WLudy1012/RolithaxLauncher-website)：项目官网与下载页。

## 反馈

在 [Issues](https://github.com/WLudy1012/RolithaxLauncher/issues) 提交问题，请附上版本号与脱敏后的日志（设置 → 运行日志）。崩溃后应用会提示一键提交。

## 开源协议

基于 [GNU AGPL-3.0](LICENSE) 开源，与上游 [MSLX](https://github.com/MSLTeam/MSLX) 一致。Copyright (C) 2026 WLudy1012。
第三方组件与内嵌运行时的许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，应用内「设置 → 关于 → 合规与许可」可离线查看。

## 免责声明

本应用是**独立第三方应用**，与 MSLTeam 不存在任何责任、关联、代理或隶属关系。管理服务器实例可能涉及数据修改、文件读写与进程启停，公共目录授权与 Shizuku（shell 级）授权均属高权限，由此产生的一切后果由使用者自行承担。完整声明见 [DISCLAIMER.txt](DISCLAIMER.txt)，首次启动时需阅读并同意。
