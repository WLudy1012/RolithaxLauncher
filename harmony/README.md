# HarmonyOS NEXT 应用

这是 HarmonyOS NEXT 的原生 ArkTS 应用。可以保存和切换多个 Daemon 连接，搜索实例，查看详情，执行启动、停止和重启，并从控制台查看日志和发送命令。文件页支持浏览目录、编辑不超过 512 KiB 的文本文件，以及通过系统文件选择器上传和下载不超过 1 GiB 的文件。

连接使用系统信任的 HTTPS 证书。保存连接时，API Key 和对应地址由 Asset Store Kit 管理，密钥仅在连接时读取。关闭连接会取消请求并清理会话。RCP 禁止自动重定向，避免密钥发往其他地址。修改请求取消或超时后，Daemon 可能已经执行，请先刷新确认状态。

REST JSON 响应按字节限制为 4 MiB，接收完成后再解码 UTF-8。文件下载先写入应用缓存，再通过文件选择器保存。上传使用 Daemon 分片接口。控制台使用 SignalR JSON 长轮询，Daemon 需要启用该传输方式。连接目前使用 API Key。

安装 JDK、支持 OHOS 的 Kuikly Kotlin/Native 工具链和 [HarmonyOS SDK](https://developer.huawei.com/consumer/cn/download/)。工程目标为 HarmonyOS 5.1.0 API 18，最低兼容 API 12，当前只打包 ARM64。将 `OHOS_SDK_HOME` 指向包含 `native` 的 SDK 根目录，在仓库根目录运行 `./gradlew -p harmony/kmp-domain prepareHarmonyNative`，Windows 使用 `gradlew.bat`。任务生成共享领域库和 C 头文件，把领域库及配套 C++ 运行库复制到 `entry/libs/arm64-v8a`。

用 DevEco Studio 打开本目录并构建 `entry` HAP。命令行构建使用 DevEco Studio 提供的 Node.js、ohpm、Hvigor 和完整 SDK。在本目录运行 `ohpm install`，再调用 Hvigor 执行 `--mode module -p module=entry@default assembleHap`。设备安装需要在本地配置签名，签名材料不得提交。

标准 Kotlin Multiplatform 不提供 HarmonyOS 目标。`kmp-domain` 使用腾讯 Kuikly Kotlin/Native 编译共享模型和响应解码逻辑。实例与文件响应经 N-API 交给该库处理，网络使用 ArkTS RCP。

已用 OpenHarmony API 18 SDK 验证 ARM64 领域库与 N-API 桥的链接、应用资源编译，以及 ArkTS 静态检查。静态检查使用参考华为 API 文档的临时 RCP 类型声明。文件传输、配置存储和控制台流程通过模拟接口验证。华为 SDK 的 HAP 构建、安装和真机联调尚待验证。

[鸿蒙原生桥接验证](../.github/workflows/harmony-native.yml)合入默认分支后，可在 GitHub Actions 手动运行。该工作流使用公开的 OpenHarmony API 18 SDK 编译 ARM64 领域库和 N-API 桥，只缓存原生 SDK。完整 HAP 仍需在华为工具链中构建。
