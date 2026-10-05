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

## 分支、Tag 与远端

- 用户未明确要求发版时，只处理代码分支，不自行创建 `v*` Tag 或 Release。
- 用户明确指定 Tag 时，先确认目标提交和 Tag 是否已经存在，再按项目版本后缀规则创建。
- 用户指定只推送某个远端时，不把 Tag 或源码额外推到其他远端。
- 推送完成后分别核对 GitHub 和 CNB 的目标分支或 Tag；远端 ref 对齐不等于 Actions/CNB 构建成功，CI 状态要单独确认。
- CNB 源码同步遇到非快进时先获取远端并检查新增提交，再按工作流约定合并或安全拒绝覆盖；不能盲目强推抹掉他人提交。

## GitHub Actions 与 CNB

- Android 工作流负责 Pull Request、分支构建、Debug/Release 校验、工件上传以及 main 源码镜像同步。
- Release 工作流只由 `v*` Tag 触发，生成签名发布物、SHA-256 校验信息、Release notes，并按约定同步 CNB。
- CNB 工作流负责 main 构建和 Tag 发布；阶段名称、任务名称和关键输出以中文为主，保留 Gradle、Android、Release、CNB 等技术名词。
- 签名和镜像认证只通过 CI 的受保护配置注入，具体凭据不进入仓库、脚本、文档或日志。
- CNB tag 构建从项目受保护变量读取 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_PASSWORD`；不通过 `pipeline imports` 跨仓库读取密钥文件，避免密钥仓库资源权限阻断流水线初始化。
- GitHub Actions 使用维护中的 Node 运行时和 Action 版本；缓存只保存可安全复用的依赖，不覆盖 runner 预装且可能无写权限的 Android SDK 目录。
- Issue 自动评审使用固定版本的 Copilot CLI 和稳定模型配置；模型或 CLI 不可用时降级为警告，不阻断 Issue 流程。

## 版本和 Release notes

- 无后缀 Tag 表示稳定版，`-Beta` 表示测试版，`-Force` 表示强制更新。
- 稳定版说明从上一稳定 Tag 起汇总；Beta 说明从上一 Beta Tag 起汇总；不能在找不到上一 Tag 时把整个仓库历史全部列入 Release notes。
- Release 同时提供完整包和精简包时，资产名称、是否内嵌运行时和 SHA-256 必须清楚可辨；缺失或未校验的运行时不应伪装成可用附件。
