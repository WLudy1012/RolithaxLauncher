package com.wludy.rolithax.launcher.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

private enum class SettingType { TEXT, INTEGER, BOOLEAN }

private data class SettingField(val key: String, val label: String, val type: SettingType)

private val settingGroups = listOf(
    "基本信息" to listOf(
        SettingField("name", "实例名称", SettingType.TEXT),
        SettingField("base", "基础路径", SettingType.TEXT),
        SettingField("expireTime", "过期时间", SettingType.TEXT),
    ),
    "启动配置" to listOf(
        SettingField("java", "Java 路径", SettingType.TEXT),
        SettingField("core", "核心文件名", SettingType.TEXT),
        SettingField("minM", "最小内存（MB）", SettingType.INTEGER),
        SettingField("maxM", "最大内存（MB）", SettingType.INTEGER),
        SettingField("args", "启动参数", SettingType.TEXT),
    ),
    "运行行为" to listOf(
        SettingField("autoRestart", "崩溃自动重启", SettingType.BOOLEAN),
        SettingField("forceAutoRestart", "强制自动重启", SettingType.BOOLEAN),
        SettingField("runOnStartup", "守护程序启动时自启", SettingType.BOOLEAN),
        SettingField("ignoreEula", "忽略 EULA", SettingType.BOOLEAN),
        SettingField("forceJvmUTF8", "强制 JVM UTF-8", SettingType.BOOLEAN),
        SettingField("allowOriginASCIIColors", "允许原始 ASCII 颜色", SettingType.BOOLEAN),
        SettingField("monitorPlayers", "监控玩家", SettingType.BOOLEAN),
        SettingField("forceExitDelay", "强制退出延迟（秒）", SettingType.INTEGER),
        SettingField("stopCommand", "停止命令", SettingType.TEXT),
        SettingField("yggdrasilApiAddr", "外置登录地址", SettingType.TEXT),
    ),
    "编码与路径" to listOf(
        SettingField("inputEncoding", "输入编码", SettingType.TEXT),
        SettingField("outputEncoding", "输出编码", SettingType.TEXT),
        SettingField("fileEncoding", "文件编码", SettingType.TEXT),
        SettingField("serverPropertiesPath", "server.properties 路径", SettingType.TEXT),
        SettingField("pluginsPath", "插件目录", SettingType.TEXT),
        SettingField("modsPath", "模组目录", SettingType.TEXT),
        SettingField("worldPath", "地图目录", SettingType.TEXT),
        SettingField("regionPath", "Region 目录", SettingType.TEXT),
    ),
    "备份" to listOf(
        SettingField("backupMaxCount", "备份数量上限", SettingType.INTEGER),
        SettingField("backupDelay", "备份间隔（秒）", SettingType.INTEGER),
        SettingField("backupPath", "备份路径", SettingType.TEXT),
    ),
)

@Composable
internal fun InstanceSettingsEditor(
    content: String,
    onContentChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val settings = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull()
    if (settings == null) {
        Text("配置不是有效的 JSON 对象，请在 JSON 模式中修正。", modifier = modifier, color = MaterialTheme.colorScheme.error)
        return
    }
    fun update(key: String, value: JsonElement) {
        onContentChange(JsonObject(settings.toMutableMap().apply { put(key, value) }).toString())
    }
    val sections = settingGroups.map { (title, fields) -> title to fields.filter { field ->
        val value = settings[field.key]
        value != null && (value is JsonNull || value is JsonPrimitive && when (field.type) {
            SettingType.TEXT -> value.isString
            SettingType.INTEGER -> !value.isString && value.intOrNull != null
            SettingType.BOOLEAN -> !value.isString && value.booleanOrNull != null
        })
    } }.filter { it.second.isNotEmpty() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (sections.isEmpty()) Text("当前配置没有可在表单中编辑的常用字段。")
        sections.forEach { (title, fields) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                fields.forEach { field ->
                    val value = settings[field.key] as? JsonPrimitive
                    when (field.type) {
                        SettingType.BOOLEAN -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(field.label, Modifier.weight(1f))
                            Switch(checked = value?.booleanOrNull == true, onCheckedChange = { update(field.key, JsonPrimitive(it)) }, enabled = enabled)
                        }
                        SettingType.TEXT, SettingType.INTEGER -> OutlinedTextField(
                            value = if (value == null || value is JsonNull) "" else value.content,
                            onValueChange = { text ->
                                when (field.type) {
                                    SettingType.TEXT -> update(field.key, JsonPrimitive(text))
                                    SettingType.INTEGER -> if (text.isEmpty()) update(field.key, JsonNull) else text.toIntOrNull()?.takeIf { it >= 0 }?.let { update(field.key, JsonPrimitive(it)) }
                                    SettingType.BOOLEAN -> Unit
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = enabled,
                            label = { Text(field.label) },
                            singleLine = true,
                        )
                    }
                }
            }
        }
    }
}
