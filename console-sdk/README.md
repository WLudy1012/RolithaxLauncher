# Rolithax 控制台插件 SDK

控制台插件把服务端日志转成面板状态，也能为宿主提供预定义的命令操作。插件装入 Rolithax 客户端的实例控制台。Android 支持本机和远端实例，Windows 支持远端 Daemon。

## 构建和运行

需要 JDK 17 或 21，并按项目说明准备 Android SDK。运行 `./gradlew :console-sdk:installDist`，Windows 使用 `gradlew.bat :console-sdk:installDist`。可执行脚本在 `console-sdk/build/install/rolithax-console-sdk/bin`，将该目录加入 PATH。

```sh
rolithax-console-sdk init paper-panel example.paper-panel
rolithax-console-sdk validate paper-panel
rolithax-console-sdk replay paper-panel paper-panel/fixtures/logs.txt paper-panel/fixtures/expected.json
rolithax-console-sdk pack paper-panel paper-panel.zip
```

`init` 生成能独立使用的模板仓库目录，包含合成日志、预期状态、README 和 GitHub Actions。`replay` 输出面板快照，可选的预期状态文件是字段到字符串的 JSON 映射。只要有一个指定字段不符，命令就返回非零退出码。回放在本地运行，命令始终关闭。

在目标实例的控制台面板导入 ZIP。修改后重新导入即可在同一会话里换版。候选包校验失败会保留上一版，导入或重放日志不会发送命令。导入与授权随当前工作区会话生效。

## 包格式

ZIP 根目录只包含三个 UTF-8 文件。`plugin.json` 最多 8 KiB，`ui.xml` 和 `rules.yaml` 各最多 64 KiB。SDK 输出标准 ZIP，宿主限制解包后的实际大小，拒绝额外条目、重复条目和目录。由同一编译器在打包和载入时验证内容。

### plugin.json

字段见 [JSON Schema](spec/plugin.schema.json)。`id` 使用命名空间，例如 `example.paper-panel`。`rolithax.paper` 为内置插件保留。`server` 记录适用服务端系列，例如 `paper`。作者应在模板 README 写明实际验证的服务端版本和日志语言。当前由用户选择适用实例。

### ui.xml

根节点为 `<panel title="…">`。所有控件都必须有唯一的 `id` 和可读的 `label`。`field` 与 `action` 只接受静态标识符。字段和动作标识符以小写 ASCII 字母开头，由小写字母、数字和连字符构成，最多 64 字符。

| 节点 | 额外属性与行为 |
| --- | --- |
| `section` | 可包含子控件 |
| `text` | 可选 `field`，绑定文本 |
| `status` | 必填 `field`，显示状态 |
| `list` | 必填 `field`，每行一条记录 |
| `field` | 必填 `field`，声明表单输入名 |
| `button` | 必填 `action`，引用 YAML 命令 ID |

布局最多 128 个控件，最多八层。根节点和分组内只写控件，其余节点可用自闭合形式。属性支持 `&amp;`、`&lt;`、`&gt;`、`&quot;`、`&apos;`。布局只允许上述节点和属性。宿主负责主题、布局、权限确认和命令结果展示。

### rules.yaml

使用两空格缩进的 YAML 映射子集。标量用双引号，允许独占一行的注释。每个区段最多 128 条，单条标量最多 512 字符。`initial` 提供字段初值，`rules` 按声明顺序更新字段，`commands` 声明可申请授权的操作。

```yaml
initial:
  activity: ""
rules:
  player-joined:
    suffix: " joined the game"
    field: "activity"
    operation: "append"
    capture: "player-event"
commands:
  refresh-players:
    text: "list"
```

每条规则至少提供 `prefix`、`suffix`、`contains` 之一，同时提供的条件必须全部满足。匹配使用去掉常见 Minecraft 时间和日志级别前缀后的消息。`operation` 默认为 `set`，`append` 保留最近 16 项。`value` 和 `capture` 二选一。捕获作用于去掉前后缀后的消息：

- `text` 返回文本，截到 512 字符。
- `player` 校验单个 Java 版玩家名，`player-event` 额外保留匹配的事件全文。
- `players` 将逗号加空格分隔的玩家名转换成逐行列表。
- `online`、`online-summary` 校验 Paper 风格的在线玩家查询输出，返回列表或人数摘要。

`commands` 的 `text` 可以是固定命令名，例如 `list`。需要玩家参数时，额外写 `player: "target"`，命令写成 `"kick {target}"`，再在 XML 中声明 `field="target"` 的输入控件。运行时只接受 1–16 位 ASCII 字母、数字和下划线。日志捕获无法填入命令参数。宿主按实例授权动作，并在第三方命令提交前显示准确内容。

## 编写和测试

为每一种支持的服务端和日志语言收集脱敏样本，记录预期字段。加入与正常事件相似的聊天行、截断行和不匹配输出。解析出来的数据应带日志证据，遇到断线或缺口由宿主提示可能过期。Daemon 的提交回执与游戏实际执行结果分开显示。

当前公开契约是 SDK 1 的声明式包。Lua 隔离和 Lua SDK 属于下一阶段，需在 Android 和 Windows 分别通过进程隔离验收。
