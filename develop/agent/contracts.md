# 外部接口与协议契约

## Daemon REST

Daemon 接口使用统一响应包装：

```json
{
  "code": 200,
  "message": "...",
  "data": {}
}
```

客户端模型应通过 `ApiResponse<T>` 解包 `data`，不能把整个响应体直接当作业务对象。常用契约包括：

- 状态：`GET /api/status`
- 实例：`GET /api/instance/list`、`GET /api/instance/info?id=...`
- 实例动作：`POST /api/instance/action`
- 创建与取消：`POST /api/instance/createServer`、`POST /api/instance/cancelCreation`
- 删除：`POST /api/instance/delete`，删除参数放在 JSON 请求体中
- 文件：`/api/files/instance/...`、`/api/files/upload/...`
- Java：`GET /api/java/list`
- 用户与管理：`/api/user/...`、`/api/admin/user/...`
- FRP：`GET /api/frp/list`

认证凭据由统一网络客户端注入并从安全存储读取；文档、日志、异常和截图中不得出现凭据值。

## 地址与 TLS

`ApiClient.normalizeDaemonUrl` 的默认行为是：去除首尾空白和多余斜杠；缺少协议时补 HTTPS；未明确允许明文时把 `http://` 升级为 `https://`。只有用户在连接页明确启用并确认风险后，才允许可信内网使用 HTTP。

Daemon 可能使用自签证书。客户端对用户自己配置的 Daemon REST、SignalR 和 WebSocket 使用统一的自签证书连接配置；MSLAPI、GitHub 等公共服务继续使用系统证书链。Retrofit 的 `baseUrl` 必须保留结尾斜杠。

## SignalR Hub

| Hub | 客户端操作 | 主要推送 |
| --- | --- | --- |
| `/api/hubs/instanceControlHub` | 加入/离开实例组、发送命令 | 日志、命令结果、EULA 请求 |
| `/api/hubs/updateProgressHub` | 加入更新组 | 更新消息、进度和错误 |
| `/api/hubs/creationProgressHub` | 跟踪/取消跟踪创建任务 | 创建消息和进度 |
| `/api/hubs/system` | 开始/结束监视 | 本机与从节点 CPU、内存和时间戳 |

对象推送必须注册为对应的数据类接收。不要把对象载荷注册为 `String` 再自行解析，否则 GsonHubProtocol 可能静默丢弃参数。连接重试要带连接代际校验，旧连接关闭后不能继续写入当前状态。

## MSLAPI

MSLAPI 也使用 `{ code, message, data }` 包装。服务端核心、Java 运行时和版本下载接口由 `MslJavaApi`、`MslServerCoreApi` 等专用客户端调用；字段解析必须兼容 `data` 为对象或数组的实际返回形态。请求必须使用包含 `Rolithax Launcher` 的 User-Agent，并遵守服务方公开的单 IP 限流：大陆地区 20 QPS，海外（含港澳台）100 QPS。

集成 MSLAPI 下载服务的界面需要同时展示准确署名、MSL Logo 和 `https://mslmc.cn` 官网链接。不能把 MSLAPI 当作没有归属的公共镜像，也不能绕过响应中的摘要校验。

## 更新元数据（GitHub / CNB）

更新检查的元数据源按「GitHub → CNB」回退，两者都不可用时失败关闭，不猜测版本：

- 首选 GitHub Releases API（`api.github.com`，`repos/WLudy1012/RolithaxLauncher/releases`）。
- GitHub 不可达（大陆网络常见）时回退 CNB，全部匿名可读、不经任何第三方代理：
  - tag 发现：`GET https://cnb.cool/WLudy/RolithaxLauncher/info/refs?service=git-upload-pack`，按 `refs/tags/<tag>` 提取；
  - 单个 release：`GET https://cnb.cool/WLudy/RolithaxLauncher/-/releases/tag/<tag>`，解析标签页服务端渲染的 `__NEXT_DATA__` 中 `props.pageProps.releasesDetailData.release`，取 `assets`（`name`/`path`/`hashValue`/`sizeInByte`）、`body`、`isPrerelease`。
- 两个源解析后走同一套渠道过滤、版本比较与 APK 资产挑选逻辑；CNB 路径单次检查最多拉取 5 个标签页。
- 强制更新判定：CNB 路径用全部候选 tag 的 `-Force` 后缀，加上实际取到页面的最新 release 说明中是否含「强制更新」（旧版兼容）。
- CNB 标签页属于 Web 载体而非开放 API，解析失败按「该版本无可用元数据」跳过更早版本，不降级到无校验数据；请求带应用 User-Agent，响应体限 4MB。

## Rolithax Daemon 插件

远程扫码、图标和云端资源直装使用插件前缀：

```text
/api/plugin/mslx-plugin-rolithax/...
```

插件包标识为 `mslx-plugin-rolithax`，最低支持的 MSLX Daemon 版本为 `1.5.10.2`。插件程序集、目录和前端资源继续使用 Rolithax 命名。

当前使用的路由包括：

- `/pair/codes`：生成一次性配对码
- `/pair/redeem`：兑换二维码载荷
- `/icon/server/{id}`：实例图标
- `/resources/install`：在 Daemon 所在机器创建资源安装任务
- `/resources/install/{id}`：查询安装任务
- `/resources/install/{id}/cancel`：取消安装任务

客户端不应恢复已移除的旧版 `/api/plugins/...` 兼容路由。资源安装由 Daemon 侧下载并落地，客户端不把远程文件先下载再上传一遍。

## 下载与文件契约

- 下载必须流式处理，不能把大型核心、整合包或图片整体读入内存。
- 可验证的下载必须提供完整 SHA-256；摘要缺失、不完整或不匹配时失败关闭。
- 下载写入临时文件，校验完成后再原子替换目标文件。
- ZIP、TAR 和 Modpack override 解压必须限制路径、单文件大小、总大小和条目数量。
- 图标响应需要限制字节数，解码前读取尺寸并按显示尺寸采样，避免多个大图并发造成 OOM。
