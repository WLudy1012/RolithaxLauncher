# 第三方组件与许可

**正本在应用里，也随 APK 和桌面应用打包**：

- 文件：[`app/src/main/assets/legal/THIRD_PARTY_NOTICES.md`](app/src/main/assets/legal/THIRD_PARTY_NOTICES.md)
- 应用内：设置 → 关于 → 合规与许可 → 第三方组件与许可
- 桌面分发：应用资源目录中的 `legal/`，包含清单与 java-keyring 的 BSD 许可文本
- 内嵌 Java 运行时的来源与校验值另附一份说明：
  [`app/src/jreRuntime/assets/jre/NOTICE-jre17.txt`](app/src/jreRuntime/assets/jre/NOTICE-jre17.txt)

内容涵盖：上游 MSLX / MSLAPI 与本应用的许可关系（AGPL-3.0 + 第三方身份声明）、
Android OpenJDK 构建产物（Java 17 内嵌，Java 8 / 21 / 25 下载，GPL-2.0 with Classpath Exception）、
以及 AndroidX / Compose / Kotlin / Retrofit / OkHttp / Gson / SignalR / Coil / XZ /
Commons-Compress / Shizuku，以及共享代码和桌面端的 Ktor、Compose Multiplatform、
Skiko、java-keyring 与 JNA 等库的来源和许可证。

本应用是**独立第三方应用**，与 MSLTeam 无隶属、代理或责任关系；
使用风险与开源义务的完整表述见 [`DISCLAIMER.txt`](DISCLAIMER.txt)（与首次开屏免责协议同一份文本）。

> 只维护这一份清单：新增/升级依赖时同步修改对应 Gradle 配置与上面的正本，
> 本页只作入口，不重复列条目，避免两处漂移。
