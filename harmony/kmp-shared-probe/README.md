# 鸿蒙共享模块依赖探针

独立读取 `shared/src/commonMain/kotlin` 并沿用其依赖坐标，使用 Kuikly Kotlin/Native 工具链检查 OHOS 编译；不改变主工程。探针仅用于诊断依赖和编译，不能代替 HarmonyOS HAP 构建与真机验证。
