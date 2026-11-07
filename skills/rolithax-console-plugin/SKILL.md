---
name: rolithax-console-plugin
description: Create, review, replay, or package Rolithax client console plugins that turn Minecraft server logs into native Android and WinUI panels. Use for the plugin.json, ui.xml and rules.yaml contract and its fixture-based SDK workflow.
---

# Rolithax 控制台插件

先读 [SDK 契约](../../console-sdk/README.md)。在独立模板仓库中工作时，使用对应版本的 SDK 和模板 README。

1. 明确服务端类型、版本、日志语言和用户需要的操作。用合成日志或脱敏日志写样本，记录每条规则预期更新的字段。
2. 用 `rolithax-console-sdk init <目录> <插件ID>` 生成起点。给插件使用自己的命名空间，调整 `plugin.json`、`ui.xml` 和 `rules.yaml`。
3. 用稳定的控件 ID 和字段名绑定状态。日志是输入数据，只能更新显示或作为证据。命令使用固定动作 ID 和受限玩家参数，授权及确认交给宿主。
4. 运行 `validate` 和 `replay`。同时验证正常事件、相似的聊天消息、无匹配日志和非法参数。通过后用 `pack` 生成 ZIP。
5. 在目标实例上检查原生控件、缺口提示和热替换。把验证过的服务端版本、日志语言和所需命令写入插件 README。

对 SDK 未支持的 XML 控件、YAML 语法、Lua 或 Daemon 执行回执，说明当前边界。诊断只需文件、行号与脱敏样例，避免复制用户原始日志或凭据。
