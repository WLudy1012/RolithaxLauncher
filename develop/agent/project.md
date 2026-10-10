# 项目概况

## 产品身份

Rolithax Launcher 是面向 MSLX Daemon 的 Android 客户端，也支持在 Android 设备上直接运行 Minecraft Java 服务端。项目以 Kotlin 和 Jetpack Compose 为主，遵循 AGPL-3.0，并作为独立第三方应用与上游 MSLTeam 保持清晰的项目边界。

- 系统显示名：`Rolithax Launcher`
- Android `namespace` / `applicationId`：`com.wludy.rolithax.launcher`
- 上游项目：MSLX Daemon
- MSLAPI 署名：使用相关下载服务的页面需要显示“本服务由MSL开服器提供”，并提供 MSL Logo 与官网链接 `https://mslmc.cn`
- 旧包名和旧插件标识只用于理解历史迁移，新的代码、资源和文档使用 Rolithax 命名

包名和签名已经改变，旧版安装不覆盖新版；用户数据通过应用内的加密导出/导入迁移。迁移逻辑不能把 Android Keystore 密钥本身导出，也不能把凭据写入明文归档。

## 当前构建基线

版本信息以 `app/build.gradle.kts` 为准。当前源码检查到的构建基线如下：

| 项目 | 当前值 |
| --- | --- |
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.2 |
| Gradle Wrapper | 8.9 |
| Java source / target | 17 |
| `compileSdk` / `targetSdk` | 35 |
| `minSdk` | 24 |
| Jetpack Compose BOM | 2024.10.00 |
| Navigation Compose | 2.8.2 |
| Retrofit / OkHttp | 2.11.0 / 4.12.0 |
| SignalR Java Client | 8.0.8 |
| DataStore Preferences | 1.1.1 |
| Room | 2.6.1 |
| Paging | 3.3.2 |
| Coil Compose | 2.7.0 |
| Haze | 1.1.1 |
| Kotlin Coroutines | 1.9.0 |
| XZ / Commons Compress | 1.10 / 1.27.1 |
| Shizuku | 13.1.5 |
| ZXing Android Embedded | 4.3.0 |
| NDK | 28.2.13676358 |

版本名和版本号不要在文档中手工猜测；发布前读取 Gradle 配置和目标 Tag。

## 主要功能

- 保存并管理多台 MSLX Daemon，统一查看远程和本地实例。
- 连接、创建、启动、停止、重启、删除和设置 Minecraft 服务端实例。
- SignalR 实时控制台、命令发送、EULA 处理和 ANSI 日志着色。
- 本机开服：内嵌 Android JRE、进程内 JVM、可选 Shizuku 增强引擎、前台服务保活。
- 资源中心：Modrinth 资源、MSLAPI 服务端核心、Room 缓存、依赖/冲突检查和目标端安装。
- 服务端文件管理、插件/模组管理、`server.properties` 编辑、备份与加密迁移。
- 稳定版和 Beta 更新渠道、应用内下载校验、运行日志、崩溃报告与首次使用导览。
- 暗色主题、自定义外观、MSLAPI 署名与第三方许可展示。

## 目录结构

```text
app/src/main/java/com/wludy/rolithax/launcher/
├── RolithaxApplication.kt        Application 与依赖容器入口
├── MainActivity.kt                单 Activity、免责门控、更新与崩溃入口
├── localengine/                   NativeVm 与 JNI 声明
├── data/
│   ├── model/                     REST、创建、文件、资源等数据模型
│   ├── remote/                    Retrofit API、SignalR Hub、网络客户端
│   ├── localengine/               本机实例、JRE、引擎、保活和文件补全
│   ├── resources/                 Provider、Room、Paging、资源安装
│   ├── InstanceRepository.kt      Daemon 实例 REST 与控制台资源
│   ├── DaemonRegistry.kt          多 Daemon 连接与延迟状态
│   ├── ServerCatalog.kt           本机与远程实例聚合
│   ├── SettingsStore.kt           DataStore 设置持久化
│   ├── CryptoManager.kt           Android Keystore 加密边界
│   ├── BackupManager.kt            加密导出/导入
│   ├── AppLogger.kt                脱敏运行日志
│   └── AppContainer.kt             手动依赖注入
└── ui/
    ├── home/                      主页、一言、负载和 Daemon 概览
    ├── connect/                   连接、扫码配对
    ├── instances/                统一实例列表
    ├── console/                  远程/本地统一控制台
    ├── create/                   创建实例向导
    ├── servers/                  服务端总览与资源中心
    ├── settings/                 设置、外观、文件、插件模组和日志
    ├── user/                     用户中心
    ├── update/                   更新、免责和崩溃报告
    ├── navigation/               AppNavHost 与路由
    └── theme/                    Rolithax 主题与玻璃表面

app/src/main/cpp/                  进程内 JVM Native 桥接
app/src/main/assets/               法律和第三方许可资料
app/src/jreRuntime/assets/        完整版 APK 的内嵌运行时资源
```

## 功能边界

- 客户端不替代 MSLX Daemon 的权限模型；Daemon 和 Rolithax 插件是远程操作的最终授权边界。
- 自建 Daemon 的自签证书可由客户端连接层信任，官方 MSLAPI、GitHub 等公共服务仍使用系统信任链。
- 进程内 JVM 受 Android 进程生命周期限制；需要多实例并行、原地重启或更自由的 Java 版本时使用 Shizuku 增强模式。
- 真实设备、公共存储权限、Shizuku 授权、远端工作流和外部下载源属于运行环境验收，不能只用本地编译结果替代。
