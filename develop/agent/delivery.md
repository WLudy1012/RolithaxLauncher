# 构建、提交、CI 与发布

## 代码与文档

- 使用中文沟通，代码注释保持少而必要；注释只解释约束、协议或不明显的原因。
- Kotlin 和 Compose 代码优先保持现有职责边界，不为小改动随意拆分 Gradle 模块。
- 网络、文件、DataStore、数据库和 Native 操作必须有明确的协程所有者和错误处理。
- 新增公共接口时先检查当前调用方、取消语义、失败语义和生命周期，再决定是否引入包装层。

## 本地验证

根据改动范围选择最窄但足够的验证：

```text
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :shared:desktopTest :desktop:test
./gradlew :desktop:createDistributable
git diff --check
```

改动工作流或 JSON/YAML 时，要额外做语法解析；改动 Native、存储、权限、下载或 UI 交互时，构建通过不等于真机验收通过，最终报告要区分本地验证和外部验证。

## Commit 规则

提交首行使用 `type: 概述`，正文用 `- ` 逐项列出：

- 改动的文件或模块。
- 修复的问题和行为变化。
- 相关 API、数据格式或兼容边界。
- 实际执行的验证及其结果。

提交说明应能直接作为 Release notes 的输入，不能只写一句模糊标题。涉及强制更新时，必须在提交正文和发布说明中明确写出“强制更新”。

## 分支模型

- `main` 受 GitHub 分支保护：禁止直接推送、强制推送和删除，只能通过 Pull Request 合并，且必须通过状态检查「构建验证」。
- 日常开发推送到 `dev`，触发 Android 和桌面验证。需要合入 `main` 时从 `dev` 发 Pull Request。
- 发版只通过推送 `release*` 分支完成（见下文「release 分支发布」）。**推送 `release*` 分支等同于发版**，用户未明确要求时不得推送。
- 不要把 `main` 当开发分支；不要在本地直接向 `origin/main` 推送。
- 历史遗留：仓库曾有名为 `dev` 的 tag 和 GitHub Release（已下线的 Actions 更新渠道），与 `dev` 分支同名；命令行操作 `dev` 分支时使用 `refs/heads/dev` 全名。

## release 分支发布

`.github/workflows/release-branch.yml` 在推送 `release*`（含 `release/**`）分支时依次执行：

1. **校验版本**。分支名去掉 `release` 前缀和分隔符后加 `v` 即为 tag。例如 `release/1.8.1-Beta` 对应 `v1.8.1-Beta`。`versionName` 必须匹配版本号。首次合并要求 `versionCode` 大于 `main` 且 tag 未被占用。
2. **构建验证**。共用 Android 验证 Action，运行单元测试、构建 Debug/Release 并检查 APK 内容。作业名保留为分支保护要求的「构建验证」。
3. **合并到 main 并打 tag**。创建 Pull Request，以已验证的提交 SHA 锁定合并目标，在合并提交上创建 tag。重试时可识别同一提交已完成的合并和 tag。
4. **发布和同步**。`release.yml` 锁定 tag 对应的提交，并行构建 `RolithaxLauncher.apk`、Windows x64 安装 EXE 和 Linux x64 归档。发布作业按 artifact ID 取回三端产物，验证来自 `runtimes/android` 的 JRE 附件，再计算统一的 `SHA256SUMS.txt`。必需附件上传齐全后公开同一个 Release，然后同步 CNB 源码和 tag。
5. **清理分支**。发布和同步流程完成后，只有 release 分支仍指向本次验证的提交才会删除。

发版步骤：在 `dev` 上提升 `versionName`/`versionCode` 并合并通过验证，然后 `git push origin dev:release-<版本>`。手动推送 `v*` tag 仍会触发 `release.yml`，仅作补发兜底。

### 实测经验（1.8.1 全链路验证）

- 流程可用性已在 1.8.1 正式版实测：推送 `release-1.8.1` 后依次完成版本校验（`versionName` 与分支一致、`versionCode` 大于 `main`）、构建验证、PR 合并 `main`、在合并提交上创建 tag、发布签名资产、同步 `main` 与 tag 到 CNB。
- 1.8.1 发布时，自动创建的 PR 触发了独立的 Android CI，随后该运行被取消。发布分支自己的「构建验证」完成后，才执行了合并。
- 发版后核对 tag 与 `main` 的合并提交一致，GitHub 和 CNB 都有 tag，GitHub Release 包含 Android APK、JRE 归档、Windows 安装 EXE、Linux 归档和 SHA-256 文件，随后确认已清理对应的 release 分支。

依赖的仓库设置：`main` 保护规则（Pull Request + 状态检查「构建验证」+ 禁止强推与删除）；Actions 权限开启「Allow GitHub Actions to create and approve pull requests」。`github.token` 创建的 tag 不会触发其他工作流，所以发布必须走 `workflow_call`。

## 分支、Tag 与远端

- 用户未明确要求发版时，只处理 `dev` 分支，不自行创建 `v*` Tag、`release*` 分支或 Release。
- 用户明确指定 Tag 时，先确认目标提交和 Tag 是否已经存在，再按项目版本后缀规则创建。
- 用户指定只推送某个远端时，不把 Tag 或源码额外推到其他远端。
- 推送完成后分别核对 GitHub 和 CNB 的目标分支或 Tag；远端 ref 对齐不等于 Actions/CNB 构建成功，CI 状态要单独确认。
- CNB 源码同步遇到非快进时先获取远端并检查新增提交，再按工作流约定合并或安全拒绝覆盖；不能盲目强推抹掉他人提交。

## GitHub Actions 与 CNB

- `android.yml` 在 `dev` 推送和面向 `main` 的 Pull Request 中调用 `.github/actions/verify-android`，上传 Debug APK 和测试报告。Android 环境由 `.github/actions/setup-android` 准备，SDK 组件按需安装。
- `desktop.yml` 在 `dev` 推送和面向 `main` 的 Pull Request 中构建桌面开发包，也供 Release 按指定提交调用。Linux 在 Xvfb 中运行共享层和 Compose 测试，并将应用和 Java 运行时一起归档。Windows 验证 C ABI、WinUI 运行时和 XAML 资源后生成单文件离线安装程序，日常 CI 额外提供便携 ZIP。
- [控制台 SDK](https://github.com/WLudy1012/rolithax-console-sdk) 独立构建和分发。[Paper 面板模板](https://github.com/WLudy1012/rolithax-console-plugin-template) 的 CI 使用独立 SDK 校验配置、回放日志并打包。
- `harmony-native.yml` 合入默认分支后可手动运行。它用校验过的公开 OpenHarmony API 18 SDK 编译 ARM64 原生桥接。完整 HAP 构建需要华为工具链，见 [鸿蒙构建说明](../../harmony/README.md)。
- `cnb-sync.yml` 由 `main` 推送和多平台发布共用，串行同步 CNB `main` 与可选 tag。遇到合并冲突会失败并保留 CNB 历史。
- `release.yml` 可由 release 分支流程调用，也可由手动推送的 `v*` Tag 触发。重试时只更新尚未公开的 Release 草稿。
- CNB 工作流负责 main 构建和 Tag 发布；阶段名称、任务名称和关键输出以中文为主，保留 Gradle、Android、Release、CNB 等技术名词。
- 签名和镜像认证只通过 CI 的受保护配置注入，具体凭据不进入仓库、脚本、文档或日志。
- CNB tag 构建通过 `pipeline imports` 从私有密钥仓库 `WLudy/Rolithax-secrets` 的 `main/keystore.yml` 读取 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_PASSWORD`；该文件须用 `allow_slugs` 授权 `WLudy/RolithaxLauncher`，否则流水线初始化会因资源权限失败。
- GitHub Actions 使用维护中的 Node 运行时和 Action 版本；缓存只保存可安全复用的依赖，不覆盖 runner 预装且可能无写权限的 Android SDK 目录。
- Issue 自动评审固定 Copilot CLI 版本，可通过仓库变量 `COPILOT_REVIEW_MODEL` 指定模型。分析作业只读，校验结果后由单独作业添加标签与评论。模型不可用时记录警告。

## 签名配置

发布使用当前已上线 APK 的 JKS，别名为 `wludy`。沿用原签名可以覆盖更新。流程通过 `apksigner` 验证 APK v2 和 v3。

在 GitHub 仓库打开 **Settings → Secrets and variables → Actions → New repository secret**，添加三个仓库 Secret。

| 名称 | 值 |
| --- | --- |
| `KEYSTORE_BASE64` | JKS 文件的单行 Base64 |
| `KEYSTORE_PASSWORD` | JKS 容器密码 |
| `KEY_PASSWORD` | `wludy` 条目的密码 |

PowerShell 可以通过标准输入把 Base64 直接交给 GitHub CLI。先运行 `gh auth login`，再从仓库目录执行下面的命令。把 JKS 路径换成本机实际路径，后两条会分别提示输入密码。

~~~powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path '.\keystore\rolithax-release.jks').Path)) |
    gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_PASSWORD
~~~

只走 GitHub 发布时，以上三个 Secret 即可。需要 CNB 镜像时，在 CNB 创建「密钥仓库」`WLudy/Rolithax-secrets`，通过网页编辑 `main` 分支根目录的 `keystore.yml`。

~~~yaml
allow_slugs: [WLudy/RolithaxLauncher]
allow_events: [tag_push]
KEYSTORE_BASE64: "<JKS 文件的单行 Base64>"
KEYSTORE_PASSWORD: "<JKS 容器密码>"
KEY_PASSWORD: "<wludy 条目密码>"
~~~

如需从 GitHub 自动推送源码和 tag 到 CNB，再在 GitHub 配置 `CNB_TOKEN`，使用有权推送对应 CNB 仓库的访问令牌。本地 `keystore/` 和 `keystore.properties` 仅供本机使用。[GitHub 仓库 Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets) · [CNB 密钥仓库](https://docs.cnb.cool/zh/repo/secret.html)

## 版本和 Release notes

- 无后缀 Tag 表示稳定版，`-Beta` 表示测试版，`-Force` 表示强制更新。应用按数字逐段比较版本号，正式版的版本号必须高于已发布的同号 Beta，否则 Beta 用户收不到。
- 稳定版说明从上一稳定 Tag 起汇总；Beta 说明从上一 Beta Tag 起汇总；不能在找不到上一 Tag 时把整个仓库历史全部列入 Release notes。
- Android 发布只包含一个 APK。APK 不内置 JRE，运行时归档及 SHA-256 都来自当前提交。
