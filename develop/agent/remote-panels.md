# 添加面板连接

在 Android 打开「连接」并点右上角的加号，滑动页面上方的图标选择面板。Windows 在「连接管理 → 添加连接」选择面板。验证并保存后，可进入当前账户有权访问的实例。

## 面板地址与凭据

- **MSLX Daemon**。填写 Daemon 的地址和 API Key。安装 Rolithax 扩展后可粘贴 Rolithax 中心生成的配对原文，Android 也可扫码。连接时检查扩展版本和 Schema 协议，基础管理可在缺少扩展时使用。 [Rolithax 扩展](https://github.com/WLudy1012/Rolithax-MSLX-plugin)
- **MCSL Future**。填写守护进程的地址或 `/api/v2` WebSocket 地址，例如 `wss://mcsl.example.com/api/v2`。使用 MCSL Future 的远程访问 Token。支持启动、停止和强制终止。 [MCSL Future V2 接口](https://github.com/MCSLTeam/MCServerLauncher-Future/blob/master/src/MCServerLauncher.Daemon/.Resources/Docs/apifox.json)
- **MCSManager 10**。填写 Web 面板地址和账号的 API Key。节点 ID 留空时列出该凭据可访问的实例。填写节点 ID 可限制到一个节点。 [MCSManager API Key 使用教程](https://docs.mcsmanager.com/zh_cn/apis/get_apikey.html) [MCSManager 实例 API](https://docs.mcsmanager.com/apis/api_instance.html)
- **Pterodactyl**。填写面板根地址，使用账号页面生成的 Client API Key。客户端还需要能访问面板返回的 Wings WebSocket 地址才能接收日志。 [Pterodactyl Client API 路由](https://github.com/pterodactyl/panel/blob/1.0-develop/routes/api-client.php)

默认使用 `https://` 或 `wss://`，明文 HTTP 或 WebSocket 需要单独授权。客户端使用 Android Keystore 或 Windows DPAPI 加密保存凭据。

## 功能范围

MCSL Future、MCSManager 10 和 Pterodactyl 支持实例列表、状态、面板提供的电源操作，以及原始控制台日志和命令。实例与操作范围由面板账号权限决定。MSLX Daemon 另有文件、资源安装和 Schema 控制台入口。

`shared` 的 `panels` 包提供可复用的协议客户端、连接配置和实例模型。Android 和 Windows 提供配套的连接存储和管理界面。Linux 与 HarmonyOS NEXT 的远端管理仍使用 MSLX Daemon。

## 验证状态

共享层 JVM 协议测试覆盖 MCSL WebSocket RPC、MCSManager 用户权限与分页、Pterodactyl Client API 和跨端口 Wings JWT 认证及续期。Windows C ABI 使用本机模拟接口验证 MCSL Future 的连接、实例列表、日志、命令和电源操作。

Android 真机连接本机模拟服务时，已验证三种面板的连接保存、重新安装后读取、实例列表、日志、命令和停止确认。取消停止不会提交请求，离开 MCSL/MCSManager 控制台后轮询停止。Wings 续期后只重新鉴权，历史日志每条连接仅请求一次；回归测试同时覆盖续期后继续接收日志。

三种面板的生产环境仍需端到端验收，尤其是反向代理、证书和账号权限。
