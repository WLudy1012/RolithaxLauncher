# 连接第三方面板

在 Android 打开「连接 → 其他面板 → 管理面板」，选择「添加面板」。填写地址和凭据后点「验证并保存」。验证通过后会打开该凭据可访问的实例列表，点「打开控制台」查看日志、发送命令或控制电源。

## 面板地址与凭据

- **MCSL Future**。填写守护进程的地址或 `/api/v2` WebSocket 地址，例如 `wss://mcsl.example.com/api/v2`。使用 MCSL Future 的远程访问 Token。支持启动、停止和强制终止。 [MCSL Future V2 接口](https://github.com/MCSLTeam/MCServerLauncher-Future/blob/master/src/MCServerLauncher.Daemon/.Resources/Docs/apifox.json)
- **MCSManager 10**。填写 Web 面板地址和账号的 API Key。节点 ID 留空时列出该凭据可访问的实例。填写节点 ID 可限制到一个节点。 [MCSManager API Key 使用教程](https://docs.mcsmanager.com/zh_cn/apis/get_apikey.html) [MCSManager 实例 API](https://docs.mcsmanager.com/apis/api_instance.html)
- **Pterodactyl**。填写面板根地址，使用账号页面生成的 Client API Key。查看控制台日志还需要手机能访问面板返回的 Wings WebSocket 地址。 [Pterodactyl Client API 路由](https://github.com/pterodactyl/panel/blob/1.0-develop/routes/api-client.php)

使用 `https://` 或 `wss://`。若受信任的内网只提供 `http://` 或 `ws://`，需显式勾选「允许明文 HTTP / WS」。明文连接会传输该接口的凭据。Android 使用本机密钥加密保存连接信息。

## 功能范围

这三种面板当前支持实例列表、状态、面板提供的电源操作，以及原始控制台日志和命令。实例与操作范围由面板账号权限决定。文件管理、资源安装和结构化控制台走本机实例与 MSLX Daemon 的既有入口。

`shared` 的 `panels` 包提供可复用的协议客户端、连接配置和实例模型。Android 提供连接存储和管理界面。Windows、Linux 和 HarmonyOS NEXT 的宿主尚无第三方面板入口。

## 验证状态

已通过共享层 JVM 协议测试、Android 定向回归测试、Debug APK 打包，以及 Windows Kotlin/Native 共享模块编译。协议测试覆盖 MCSL WebSocket RPC、MCSManager 用户权限与分页、Pterodactyl Client API 和跨端口 Wings JWT 认证及续期；测试使用本机模拟接口。

Android 真机连接本机模拟服务时，已验证三种面板的连接保存、重新安装后读取、实例列表、日志、命令和停止确认。取消停止不会提交请求，离开 MCSL/MCSManager 控制台后轮询停止。Wings 续期后只重新鉴权，历史日志每条连接仅请求一次；回归测试同时覆盖续期后继续接收日志。

三种面板的真实部署仍需端到端验收。这些测试不代表已验证生产面板的反向代理、证书和账号权限配置。
