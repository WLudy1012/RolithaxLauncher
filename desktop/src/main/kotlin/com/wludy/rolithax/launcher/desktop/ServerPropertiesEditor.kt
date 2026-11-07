package com.wludy.rolithax.launcher.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wludy.rolithax.launcher.data.model.PropType
import com.wludy.rolithax.launcher.data.model.SERVER_PROPERTIES_SCHEMA

private val propertyLine = Regex("^([ \\t]*)([a-zA-Z0-9_.-]+)([ \\t]*[=:][ \\t]*)(.*)$")

internal fun editableProperties(content: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var continued = false
    content.lineSequence().forEach { line ->
        val current = line.removeSuffix("\r")
        val match = if (!continued) propertyLine.matchEntire(current) else null
        val backslashes = current.takeLastWhile { it == '\\' }.length
        continued = (continued || !current.trimStart().startsWith('#') && !current.trimStart().startsWith('!')) && backslashes % 2 != 0
        if (match != null) {
            if (continued) result.remove(match.groupValues[2]) else result[match.groupValues[2]] = match.groupValues[4]
        }
    }
    return result
}

internal fun updateProperty(content: String, key: String, value: String): String {
    require('\n' !in value && '\r' !in value)
    val lines = content.split('\n').toMutableList()
    var continued = false
    var target = -1
    lines.forEachIndexed { index, line ->
        val current = line.removeSuffix("\r")
        val match = if (!continued) propertyLine.matchEntire(current) else null
        continued = (continued || !current.trimStart().startsWith('#') && !current.trimStart().startsWith('!')) && current.takeLastWhile { it == '\\' }.length % 2 != 0
        if (match?.groupValues?.get(2) == key) target = if (continued) -1 else index
    }
    if (target < 0) return content
    val ending = if (lines[target].endsWith('\r')) "\r" else ""
    val match = propertyLine.matchEntire(lines[target].removeSuffix("\r")) ?: return content
    lines[target] = match.groupValues[1] + key + match.groupValues[3] + value + ending
    return lines.joinToString("\n")
}

@Composable
internal fun ServerPropertiesEditor(content: String, enabled: Boolean, onContentChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val values = remember(content) { editableProperties(content) }
    val groups = remember(values.keys) { SERVER_PROPERTIES_SCHEMA.filter { it.key in values }.groupBy { it.group } }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("仅显示文件中已有的常用配置；其他内容会保留，可切换到文本模式编辑。", style = MaterialTheme.typography.bodySmall) }
        groups.forEach { (group, entries) ->
            item(key = group) { Text(group, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            entries.forEach { field ->
                item(key = field.key) {
                    val value = values[field.key].orEmpty()
                    if (field.type == PropType.BOOLEAN && (value == "true" || value == "false")) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(field.label)
                                Text(field.desc, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(value == "true", { onContentChange(updateProperty(content, field.key, it.toString())) }, enabled = enabled)
                        }
                    } else {
                        OutlinedTextField(value, { if ('\n' !in it && '\r' !in it) onContentChange(updateProperty(content, field.key, it)) },
                            modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
                            label = { Text(field.label) }, supportingText = { Text(field.desc) })
                    }
                }
            }
        }
        if (groups.isEmpty()) item { Text("未找到可编辑的常用字段，请使用文本模式。") }
    }
}
