# 第三方组件与许可（THIRD PARTY NOTICES）

本文件是 Rolithax Launcher 所用第三方组件与许可的**正本**，
随 APK 打包（`assets/legal/THIRD_PARTY_NOTICES.md`），应用内「设置 → 关于 → 合规与许可」可直接离线查看；桌面分发也在应用资源目录的 `legal/` 中包含这份资料。仓库根目录的 `THIRD_PARTY_NOTICES.md` 只是指向本文件的说明。

最后核对：2026-11（版本号以各模块 Gradle 配置和实际解析结果为准）。

## 一、本项目与上游项目

| 名称 | 角色 | 许可 |
| --- | --- | --- |
| Rolithax Launcher（本应用） | 面向 MSLX Daemon 的 Android 与桌面客户端 | AGPL-3.0 |
| [MSLX](https://github.com/MSLTeam/MSLX)（MSLTeam） | 被管理的守护程序（MSLX Daemon）与其桌面端 | AGPL-3.0 |
| [MSLAPI（mslapi / mslxapi）](https://mslmc.cn) | Java 运行时、服务端核心与版本元数据、下载地址、SHA-256；本服务由 MSL 开服器提供 | 未声明开源许可，仅作为外部 HTTP 接口调用 |
| Minecraft / Mojang Studios | 被管理的服务端程序本身与其 EULA | 非开源；服务端运行须自行同意 Minecraft EULA |

本应用是**独立第三方应用**，并非 MSLX 官方或 MSLTeam 发布的产品，两者之间不存在责任、
关联、代理或隶属关系；完整措辞见首次启动的「第三方免责声明」与仓库根目录 `DISCLAIMER.txt`。

## 二、Java 运行时（本机开服按需下载）

| 运行时 | 来源构建 | 许可 |
| --- | --- | --- |
| Java 17 | [PojavLauncherTeam/android-openjdk-build-multiarch](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/tag/jre17-ec28559) 发布标签 `jre17-ec28559`，架构为 arm64 和 x86_64 | OpenJDK：GPL-2.0 **with Classpath Exception**（SPDX 标识 `GPL-2.0-only with Classpath-exception-2.0`） |
| Java 21 | [aaaapai/android-openjdk-build 20260223](https://github.com/aaaapai/android-openjdk-build/releases/tag/20260223) 的 Dragonwell Android 构建 `jre21-arm64-20260223-release.tar.xz` | 同上（OpenJDK GPL-2.0 with Classpath Exception） |
| Java 25 | 同一 20260223 发布的 Android 构建 `jre25-arm64-20260223-release.tar.xz`（OpenJDK 25.0.3） | 同上（OpenJDK GPL-2.0 with Classpath Exception） |
| Java 8 | [ZalithLauncher 2](https://github.com/ZalithLauncher/ZalithLauncher2) 中 OpenJDK 8u442 的 Android/bionic 构建，包含共用类库和 arm64、x86_64 二进制 | 同上（OpenJDK GPL-2.0 with Classpath Exception） |

- 运行时归档由 [Rolithax 仓库](https://github.com/WLudy1012/RolithaxLauncher/tree/main/runtimes/android) 分发，应用在安装前按 SHA-256 校验原始字节。
- Java 8 的类库以 pack200 压缩格式（`lib/*.jar.pack`，Java 9 起已废弃）分发，安装时由随 APK
  分发的 `libunpack200.so`（OpenJDK 8 的 unpack200 工具，见下表）在设备上还原为 `.jar`。
- 每台设备的运行时安装位置：`filesDir/rolithax/runtime/<运行时标识>`（应用私有目录）。

## 三、随包分发的第三方库

| 组件（Gradle 坐标） | 版本 | 许可 |
| --- | --- | --- |
| AndroidX Core Ktx / Splashscreen / Lifecycle / Activity Compose / Compose UI & Material3 / Navigation / DataStore Preferences | 见 `libs.versions.toml` | Apache-2.0 |
| Compose Material Icons Core（图标） | BOM 2024.10.00 | 代码 Apache-2.0；图标资源遵循 Google Material Icons 的 CC-BY-4.0 |
| Kotlin 标准库（JetBrains） | 2.0.21 | Apache-2.0 |
| kotlinx-coroutines-android | 1.9.0 | Apache-2.0 |
| Retrofit + converter-gson（Square） | 2.11.0 | Apache-2.0 |
| OkHttp + logging-interceptor（Square） | 4.12.0 | Apache-2.0 |
| Gson（Google） | 2.10.1 | Apache-2.0 |
| SignalR Java Client（Microsoft，`com.microsoft.signalr:signalr`） | 8.0.8 | Apache-2.0 |
| Coil Compose（`io.coil-kt:coil-compose`） | 2.7.0 | Apache-2.0 |
| XZ for Java（`org.tukaani:xz`，解压 `.tar.xz` 运行时） | 1.10 | 0BSD（1.9 及更早为 Public Domain） |
| Apache Commons Compress（tar 归档处理） | 1.27.1 | Apache-2.0 |
| Shizuku 客户端 API / Provider（`dev.rikka.shizuku:api`、`:provider`） | 13.1.5 | Apache-2.0（按该许可第 6 条附加说明：**不得**复用 Shizuku 管理器自身的界面资源；本应用只调用 API） |
| `libunpack200.so`（`jniLibs/{arm64-v8a,x86_64}`，Java 8 类库的 pack200 解包器） | 8u442（取自 ZalithLauncher 2 的 `runtimes/jre-8` 归档，bionic 构建） | OpenJDK：GPL-2.0 **with Classpath Exception** |

### 共享代码与桌面端依赖

以下列出本轮直接使用的主要组件；具体分发平台和传递依赖以对应构建产物为准。

| 组件 | 版本 | 来源与许可 |
| --- | --- | --- |
| Ktor 客户端核心、WebSocket 和 CIO | 2.3.13 | [Ktor](https://github.com/ktorio/ktor/tree/2.3.13)，Apache-2.0；共享客户端使用核心与 WebSocket，桌面端使用 CIO |
| Compose Multiplatform | 1.7.3 | [JetBrains Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)，Apache-2.0；桌面 UI |
| Skiko | 0.8.18 | [JetBrains Skiko](https://github.com/JetBrains/skiko)，Apache-2.0；Compose 桌面渲染依赖，另见上游 [NOTICE](https://github.com/JetBrains/skiko/blob/master/NOTICE) |
| java-keyring（`com.github.javakeyring:java-keyring`） | 1.0.4 | [java-keyring](https://github.com/javakeyring/java-keyring)，BSD-3-Clause；系统凭据存储。版权声明和完整许可文本见同目录 `java-keyring-LICENSE.txt` |
| JNA / JNA Platform（`net.java.dev.jna`） | 5.15.0 | [Java Native Access](https://github.com/java-native-access/jna/tree/5.15.0)，可选 LGPL-2.1-or-later 或 Apache-2.0；本项目采用 Apache-2.0 |

各组件及其传递依赖可能另附版权、许可或 NOTICE 文件，分发时以实际产物和上游声明为准。

## 四、自研组件

| 组件 | 说明 | 许可 |
| --- | --- | --- |
| `app/src/main/cpp/rolithaxvm.c`、`app/src/main/cpp/shim/rolithaxnotag.c`（后者由 `build-shim.ps1` 产出 `librolithaxnotag-*.so`） | 进程内 JVM 桥接（`dlopen libjvm.so` + `JNI_CreateJavaVM`）、stdio 接管，以及关 Scudo 堆标签的 `LD_PRELOAD` 垫片 | AGPL-3.0（属本应用源码） |

## 五、获取完整许可证文本

- Apache-2.0：https://www.apache.org/licenses/LICENSE-2.0
- AGPL-3.0：https://www.gnu.org/licenses/agpl-3.0.html（本仓库 `LICENSE`）
- GPL-2.0 with Classpath Exception：https://openjdk.org/legal/gplv2+ce.html
- 0BSD：https://opensource.org/licenses/0BSD
- MIT：https://opensource.org/licenses/MIT
- BSD-3-Clause：本目录 `java-keyring-LICENSE.txt`；上游 https://github.com/javakeyring/java-keyring/blob/master/LICENSE

上述组件的许可证副本亦随各自发布产物提供；APK 中 `META-INF/` 目录内可找到多数 JVM 库
自带的 `LICENSE`/`NOTICE` 文件。若在分发版本时对第三方组件做了实质性修改，请以对应上游
仓库的许可声明为准复核。
