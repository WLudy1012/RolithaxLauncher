# Paper 控制台面板模板

这个示例为 Rolithax 控制台增加 Paper 启动状态、玩家进出和在线玩家查询。示例日志是合成数据。操作默认需要宿主授权。

安装 Rolithax Console SDK 后，在仓库根目录执行：

```sh
rolithax-console-sdk validate .
rolithax-console-sdk replay . fixtures/logs.txt fixtures/expected.json
rolithax-console-sdk pack . paper-panel.zip
```

将 ZIP 导入目标实例的 Rolithax 控制台。修改 XML 或 YAML 后重新打包并导入，宿主会校验候选版本，再切换面板。给自己的插件选一个唯一的命名空间，更新 `plugin.json` 中的 ID 和版本。

GitHub Actions 从 Rolithax 源码构建 SDK。若使用自己的 Rolithax 分支，可设置仓库变量 `ROLITHAX_SDK_REPOSITORY` 和 `ROLITHAX_SDK_REF`，指向包含 `console-sdk` 的 GitHub 仓库和版本。
