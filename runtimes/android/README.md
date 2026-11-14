# Android Java 运行时

本目录保存 Android 开服用的运行时归档。发布流水线按 [SHA256SUMS.txt](SHA256SUMS.txt) 校验，再把归档作为 Release 附件。APK 会在用户选择运行时后从 Rolithax 仓库下载，并在解包前再次校验。

| Java | 架构 | 归档来源 |
| --- | --- | --- |
| 8 | arm64、x86_64 | [ZalithLauncher 2 Android 构建](https://github.com/ZalithLauncher/ZalithLauncher2/tree/c1726fed049a686297ac940f35436b9cc4c2ad39/ZalithLauncher/src/main/assets/runtimes/jre-8)，OpenJDK 8u442，按类库和平台二进制拆分 |
| 17 | arm64、x86_64 | [PojavLauncherTeam Android OpenJDK](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/tag/jre17-ec28559) |
| 21 | arm64 | [aaaapai/android-openjdk-build 20260223](https://github.com/aaaapai/android-openjdk-build/releases/tag/20260223)，Dragonwell 的 Android 构建 |
| 25 | arm64 | 同一 20260223 发布中的 OpenJDK 25.0.3 Android 构建 |

上游归档按字节保留，Java 8 的原始文件名在仓库中改为 `jre8-universal.tar.xz`、`jre8-arm64-bin.tar.xz` 和 `jre8-x86_64-bin.tar.xz`，便于区分用途。运行时遵循 [GPL-2.0-only with Classpath Exception](https://openjdk.org/legal/gplv2+ce.html)。Java 8 的许可文件在共用类库归档中，其他版本的许可文件在各自的归档中。

替换运行时归档时，同步更新此处的 SHA-256 清单和 [LocalJreManager.kt](../../app/src/main/java/com/wludy/rolithax/launcher/data/localengine/LocalJreManager.kt) 中的下载记录。应用已支持的版本和架构均需要对应归档。Java 8 的类库归档需要与目标架构的二进制归档一起解包。
