# 鸿蒙 KMP 编译探针

独立实验工程，用腾讯 KuiklyBase 的 `2.0.21-KBA-010` 编译器生成鸿蒙 arm64 Kotlin/Native 动态库，不修改主工程的 Kotlin 版本或 `:shared` 依赖。探针直接引用共享资源筛选模型；`native/napi_probe.c` 已在 OpenHarmony API 18 Native SDK 下交叉链接，但尚未在设备上运行。

准备 JDK、OHOS Native SDK 后，在本目录使用 Gradle 运行 `linkDebugSharedOhosArm64`。Windows 工具链要求及候选版本见 [KuiklyUI 构建说明](https://github.com/Tencent-TDS/KuiklyUI#running-ohos-app)。实际应用的共享模型及 N-API 入口位于 `kmp-domain` 和 `entry/src/main/cpp`，完整 HAP 与设备运行仍需验证。

Windows 环境可从[华为开发者下载中心](https://developer.huawei.com/consumer/cn/download/)获取 DevEco Studio 或 Command Line Tools，并按安装向导配置所需 HarmonyOS SDK。下载、账户登录和许可协议按华为的要求由使用者操作；签名材料不得提交到仓库。`@deveco/deveco-cli` 是工具链包装，不包含上述 SDK 的替代品。鸿蒙构建完成前还需核对编译器与依赖的 OHOS 变体，以及 Ktor 网络引擎的 TLS 和取消行为。
