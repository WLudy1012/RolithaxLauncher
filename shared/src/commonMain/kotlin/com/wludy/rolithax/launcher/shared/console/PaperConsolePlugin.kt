package com.wludy.rolithax.launcher.shared.console

internal val paperPluginSource = ConsolePluginSource(
    manifest = """
        {
          "id": "rolithax.paper",
          "version": "1.0.0",
          "sdkVersion": 1,
          "server": "paper"
        }
    """.trimIndent(),
    ui = """
        <panel title="Paper 服务器">
          <section id="server" label="服务端">
            <status id="lifecycle-status" label="日志状态" field="lifecycle"/>
          </section>
          <section id="players" label="玩家">
            <text id="player-summary" label="最近一次查询" field="player-summary"/>
            <list id="online-list" label="查询返回的玩家" field="online-players"/>
            <button id="refresh" label="查询在线玩家" action="refresh-players"/>
            <list id="activity-list" label="玩家进出" field="activity"/>
          </section>
        </panel>
    """.trimIndent(),
    rules = """
        initial:
          lifecycle: "等待服务端日志"
          player-summary: "等待查询"
          online-players: ""
          activity: ""
        rules:
          started:
            prefix: "Starting minecraft server version "
            field: "lifecycle"
            value: "正在启动"
          ready:
            prefix: "Done ("
            contains: "For help, type"
            field: "lifecycle"
            value: "启动完成"
          stopping:
            prefix: "Stopping server"
            field: "lifecycle"
            value: "正在停止"
          players:
            prefix: "There are "
            field: "online-players"
            capture: "online"
          player-summary:
            prefix: "There are "
            contains: " players online:"
            field: "player-summary"
            capture: "online-summary"
          join:
            suffix: " joined the game"
            field: "activity"
            operation: "append"
            capture: "player-event"
          leave:
            suffix: " left the game"
            field: "activity"
            operation: "append"
            capture: "player-event"
        commands:
          refresh-players:
            text: "list"
    """.trimIndent(),
)
