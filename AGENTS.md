# Rolithax Launcher 项目协作索引

本文件只保留项目级入口和最低协作约定。详细架构、接口、安全、发版与历史信息位于 [`develop/agent/`](develop/agent/README.md)。

## 读取顺序

1. 先读 [`develop/agent/README.md`](develop/agent/README.md) 了解文档边界和主题索引。
2. 按任务读取对应主题文档，不要把历史记录当成当前实现。
3. 当前源码、Gradle 配置和工作流的实际行为优先于历史说明；发现冲突时修正文档。

## 最低约定

- 用户明确请求优先；项目文档提供默认行为，不替代本轮请求。
- 使用中文沟通，写代码少写注释，注释只保留能说明约束或原因的内容。
- 不把 API Key、Token、密码、私钥、签名材料、受保护配置或其他凭据写入代码、日志、提交信息和项目文档。
- 提交前按主题文档执行必要的差异检查和构建验证；未明确要求时不要擅自发版、打 Tag 或推送远端。
- 不将本地开发环境特征写入文档，而是写入根目录下的AGENTS.local.md

## 文档入口

- 项目身份与技术栈：[`project.md`](develop/agent/project.md)
- 分层架构与模块职责：[`architecture.md`](develop/agent/architecture.md)
- Daemon、MSLAPI、SignalR 与插件契约：[`contracts.md`](develop/agent/contracts.md)
- 本机开服与运行时：[`local-server.md`](develop/agent/local-server.md)
- 资源中心：[`resource-center.md`](develop/agent/resource-center.md)
- 安全、隐私与日志：[`security.md`](develop/agent/security.md)
- 构建、提交、CI 与发布：[`delivery.md`](develop/agent/delivery.md)
- 已完成演进与当前边界：[`history.md`](develop/agent/history.md)
