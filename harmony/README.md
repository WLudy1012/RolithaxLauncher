# HarmonyOS NEXT 应用

这里是 Stage 模型的 ArkTS 应用入口，包含使用 HTTPS 和 API Key 获取远程 Daemon 实例列表和详情、浏览实例文件、编辑小型文本文件以及发送启动、停止或重启请求的过渡实现。实例和文件响应由 N-API 调用共享 Kotlin 数据模型解码；网络与其余业务仍由 ArkTS 实现，尚未完成 SignalR、文件上传下载和账号持久化。鸿蒙 HAP 构建、真实设备和 Daemon 联调尚未验证。

连接仅接受系统信任证书的 HTTPS，暂不支持自签证书和 HTTP。API Key 仅驻留内存，断开或离开页面时取消请求并清除连接；不写入文件或日志。RCP 禁止自动重定向，避免 API Key 随重定向发送到其他地址。操作失败或请求取消后，服务端可能已经执行，应先刷新确认状态再重试。

当前响应大小检查发生在 RCP 完整接收响应之后，不能阻止超大响应先占用内存。正式使用前需要改为受限流式接收，并在 HarmonyOS SDK 与设备上验证超限取消、UTF-8 分片和会话复用。

先配置 JDK、支持 OHOS 的 Kuikly Kotlin/Native 工具链和 SDK，将 `OHOS_SDK_HOME` 指向包含 `native` 目录的 SDK 根目录。在项目根目录运行 `./gradlew -p harmony/kmp-domain prepareHarmonyNative`；Windows 使用 `gradlew.bat`。此任务生成共享领域库、C 头文件，并将动态库准备到 `entry/libs/arm64-v8a`。然后使用 DevEco Studio 打开本目录，安装对应的 HarmonyOS SDK 并构建 `entry` HAP。命令行构建需另外配置 Node.js、ohpm、DevEco Studio 提供的 Hvigor 和完整 SDK，先运行 `ohpm install`，再使用安装环境中的 Hvigor 执行 `--mode module -p module=entry@default assembleHap`。本仓库没有附带 `hvigorw` 包装脚本。设备安装还需按目标设备和发行方式配置签名；签名材料不得提交。

标准 Kotlin Multiplatform 不提供 HarmonyOS 目标。`kmp-domain` 使用腾讯 Kuikly Kotlin/Native 工具链和其适配的序列化库，直接编译共享模型；OpenHarmony API 18 Native SDK 下已验证领域库与 N-API C 桥的交叉链接。现有 Ktor 依赖没有 OHOS 变体，因此完整共享网络层仍需适配；完整 HarmonyOS SDK、HAP 安装和真机行为尚待验证。

`kmp-poc`、`kmp-libs-probe` 与 `kmp-shared-probe` 保留工具链和依赖兼容性的独立实验，不参与主 Gradle 工程和桌面 CI，也不能用它们的链接结果代替 HAP 或设备验收。
