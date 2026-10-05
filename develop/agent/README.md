# Rolithax Launcher 项目资料

这里是从项目开发资料整理出的脱敏、结构化索引，供后续 AI 和开发者快速定位信息。它记录项目级事实、约束和决策，不保存任何 API Key、Token、密码、私钥、签名材料、受保护配置、凭据值或本机开发环境信息。

## 资料边界

- 当前源码、`app/build.gradle.kts`、`gradle/libs.versions.toml`、工作流和资源文件是实现事实的首要来源。
- 本目录中的历史记录用于解释决策和兼容边界；如果与当前代码不一致，以当前代码为准，并在完成变更后更新对应文档。
- `develop/secret/llm.txt` 是原始开发资料的受保护来源，包含历史记录和不应扩散的内容。本目录只保留经过筛选的项目知识，不复制其中的敏感段落。
- 需要精确查询 MSLAPI 或 MSLX Daemon 的接口字段时，继续参考 `develop/secret/mslapi.txt` 和 `develop/secret/mslxapi.txt`；不要把其中的凭据或本地配置复制到公开项目文档。

## 主题索引

| 文件 | 内容 |
| --- | --- |
| [`project.md`](project.md) | 产品身份、包名、技术栈、目录结构和功能边界 |
| [`architecture.md`](architecture.md) | MVVM、依赖容器、数据流、导航和核心模块职责 |
| [`contracts.md`](contracts.md) | Daemon REST、MSLAPI、SignalR、插件路由和网络约束 |
| [`local-server.md`](local-server.md) | 本机实例、JVM 引擎、运行时、存储和保活 |
| [`resource-center.md`](resource-center.md) | Provider、Modrinth、MSLAPI、缓存、安装与依赖校验 |
| [`security.md`](security.md) | 凭据保护、HTTPS、日志、下载校验、备份和权限边界 |
| [`delivery.md`](delivery.md) | 代码风格、提交、验证、GitHub/CNB 工作流与版本规则 |
| [`history.md`](history.md) | 重要演进、当前实现依据和已知外部验收边界 |

## 更新资料的规则

新增功能时，先更新所属主题文档，再在 `history.md` 记录影响范围和验证状态。改包名、插件路由、API 契约、数据目录或发布规则时，必须同时检查 `project.md`、`contracts.md`、`local-server.md` 或 `delivery.md` 中的交叉引用。不要用提交哈希、临时构建路径或一次性运行日志代替长期项目事实。
