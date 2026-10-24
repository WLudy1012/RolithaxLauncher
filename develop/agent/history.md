# 演进记录与当前边界

本文件只保留对后续维护有帮助的阶段性决策，不记录一次性提交哈希、构建路径、凭据或本机环境信息。

## 重要演进

1. **Android 客户端基础**：从单 Daemon 控制台发展为 Kotlin + Compose 的完整实例管理应用，形成 REST、SignalR、日志、更新、免责声明和设置体系。
2. **安全基线**：加入 HTTPS 默认策略、自签 Daemon 连接、Keystore 加密、日志脱敏、下载域名和 SHA-256 校验、加密备份与路径防护。
3. **本机开服**：从早期外部进程实验转为进程内 JVM，随后增加多 Java 运行时、前台服务、统一实例目录和可选 Shizuku 子进程引擎。
4. **统一实例模型**：多 Daemon 和本机实例都由 `ServerCatalog` 聚合，`ServerRef` 成为操作寻址边界；控制台统一为远程/本地两个实现共用的 `ConsoleController`。
5. **Rolithax 品牌迁移**：包名、系统名称、插件路由和前端资源统一使用 Rolithax；旧版通过加密导出/导入迁移，不依赖旧包覆盖安装。
6. **资源中心**：首期接入 Modrinth v3 和 MSLAPI，采用 Room/Paging/Flow 缓存，支持服务端过滤、版本选择、依赖/冲突检查、本地流式安装和插件侧云端安装。
7. **CI 与发布**：GitHub Actions、CNB 和 Release notes 规则逐步统一，工作流步骤改为中文为主，缓存和镜像同步保留远端提交安全边界。
8. **界面稳定性**：Dock 提升到导航容器外部，快速点击切换加锁；暗色主题提高对比度；扫码页使用窗口 Insets；资源和设置页面补充错误、空态、重试和无障碍语义。
9. **大屏导航**：顶层导航按可用窗口宽高切换底部 Dock 与左侧导航轨；短横屏窗口优先保留底部栏，较矮导航轨收起未选中标签。
10. **毛玻璃重写**：移除按系统版本划分的伪档位与 CPU 箱式预模糊，改用 Haze（`dev.chrisbanes.haze`）对背景层做实时模糊；背景图不再限制 Android 13+；各页面卡片统一到 `MslxCard` / `GlassSurface`。DataStore 中 `glass_alpha` 与背景图路径字段保持不变，备份格式兼容。弹窗位于独立窗口，玻璃面板在其中自动降级为半透明填充。
11. **界面统一与可读性**：全站页面统一到 `MslxCard`/`GlassSurface` 卡片、`MslxSectionHeader` 分区标题与 `MslxEmptyState` 空/错误态；页面边距 16–20dp、卡片间距 12dp、卡内边距 16dp 成为默认；触控目标统一到 48dp，开关/单选改为整行可点；长文本补 `maxLines` + 省略。`StatusUi` 状态色、浅色主题 `primary/secondary/tertiary` 前景色按 WCAG AA 对比度派生（`ensureContrast`），并补齐 `MSLXShapes.extraSmall`。日志/终端等区域保持不透明自绘，不参与玻璃。
12. **资源中心分类修复与配对收尾**：Modrinth 搜索改用 `facets`，修复模组/插件分类不生效（Issue #22）；扫码配对重新兑换时携带本机已保存的同一 Daemon 的旧 API Key（`previousApiKey`），与插件端的设备替换校验配套。
13. **README 多语言**：README 提供简体中文、繁体中文和英文三份，改动功能描述时需同步三份。
14. **分支保护与 release 分支发版**：`main` 只接受 Pull Request，日常推 `dev`，推送 `release*` 分支自动校验、构建验证、合并并发布；更新下载的域名白名单补上 CNB 重定向到的 `asset.cnb.cool`（此前 CNB 下载总在重定向后被拒，只能回退 GitHub）。

## 当前实现依据

- 当前代码使用 `com.wludy.rolithax.launcher` 和系统显示名 `Rolithax Launcher`。
- 当前插件路由使用 `mslx-plugin-rolithax`；旧的第三方 Android 整合插件路由不应重新引入。
- 代码、资源和 Native 库已完成 Rolithax 命名（`RolithaxUi`、`DaemonApi`、`librolithaxvm.so` 等）。保留的 `mslx`/`MSLX` 标识均为外部契约或兼容读取：插件路由、`mslxp1:` 配对前缀、`MSLX://` 协议 URI、Keystore 别名、CI 变量 `MSLX_MAVEN_*`，以及导入时仍接受的旧备份魔数 `MSLX-BACKUP`（新备份使用 `RLTX-BACKUP` 和 `.rlxb`）。
- 当前本机引擎包含进程内 JVM 和 Shizuku 增强模式，运行时、实例和资源安装均以当前源码为准。
- 当前资源中心以 Modrinth Provider、MSLAPI 核心接口和 Room/Paging 缓存为首期能力，Provider 扩展点保留给后续站点。
- 当前版本号、构建号、依赖和发布资产以 Gradle 配置与工作流运行结果为准，不在本文件固定写死。

## 仍需外部验收的边界

- Android 真机上的扫码、状态栏、公共存储、前台服务、唤醒锁、热点和防火墙权限。
- 平板和横屏设备上的导航轨切换、短窗口布局与真实触控行为。
- 毛玻璃实时模糊在不同 GPU 上的帧率与发热，以及 API 31 及以下设备的降级外观。
- 不同设备上的进程内 JVM、Java 运行时、Shizuku、多实例和服务端核心兼容性。
- GitHub Actions、CNB 镜像同步、Release 资产下载和 Issue 自动评审的远端端到端结果。
- 跨设备加密备份恢复、旧版本迁移、异常中断后的临时文件清理。
- MSLAPI、Modrinth 和插件服务端返回数据的真实兼容性；本地编译不能替代接口实测。

这些边界应在有设备或远端运行证据后更新，不能用“本地构建成功”替代真实验收结论。
