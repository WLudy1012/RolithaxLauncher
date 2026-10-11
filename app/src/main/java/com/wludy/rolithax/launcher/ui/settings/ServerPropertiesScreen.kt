package com.wludy.rolithax.launcher.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wludy.rolithax.launcher.data.ServerRef
import com.wludy.rolithax.launcher.data.model.PropOption
import com.wludy.rolithax.launcher.data.model.PropSchema
import com.wludy.rolithax.launcher.data.model.PropType
import com.wludy.rolithax.launcher.data.model.SERVER_PROPERTIES_SCHEMA
import com.wludy.rolithax.launcher.ui.RolithaxCard
import com.wludy.rolithax.launcher.ui.RolithaxEmptyState
import com.wludy.rolithax.launcher.ui.RolithaxSectionHeader

/** 静态 schema 的派生索引：避免每次重组都重建 Set / 分组 Map。 */
private val KNOWN_PROPERTY_KEYS: Set<String> = SERVER_PROPERTIES_SCHEMA.map { it.key }.toSet()
private val PROPERTY_GROUPS: Map<String, List<PropSchema>> = SERVER_PROPERTIES_SCHEMA.groupBy { it.group }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPropertiesScreen(
    ref: ServerRef,
    onBack: () -> Unit,
) {
    val viewModel: ServerPropertiesViewModel = viewModel(
        key = "server_props_${ref.catalogKey}",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                ServerPropertiesViewModel(app, ref)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // 未保存改动基线：只在加载完成时取一次，之后与用户编辑后的值比较
    var baseline by remember { mutableStateOf<Map<String, String>?>(null) }
    val dirty = baseline != null && state.values != baseline

    LaunchedEffect(state.loading) {
        if (!state.loading && baseline == null) baseline = state.values
    }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("服务器设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!state.loading) {
                        // 无改动时禁用，避免误触重复写盘；文件缺失时仍允许保存以创建文件
                        TextButton(
                            onClick = viewModel::save,
                            enabled = !state.saving && (dirty || state.missing),
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("保存", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            state.loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "正在读取 server.properties…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                val values = state.values
                val unknownKeys = remember(values) { values.keys.filter { it !in KNOWN_PROPERTY_KEYS } }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "path") {
                        Text(
                            text = state.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (dirty) {
                        item(key = "dirty_hint") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "有未保存的改动",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    if (state.missing) {
                        item(key = "missing_hint") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "未找到该文件，保存后会自动创建。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    PROPERTY_GROUPS.forEach { (group, props) ->
                        val visible = props.filter { values.containsKey(it.key) }
                        if (visible.isNotEmpty()) {
                            item(key = "group_$group") { RolithaxSectionHeader(title = group) }
                            visible.forEach { prop ->
                                item(key = "known_${prop.key}") {
                                    PropCard(
                                        prop = prop,
                                        value = values[prop.key].orEmpty(),
                                        onValueChange = { v -> viewModel.setValue(prop.key, v) },
                                    )
                                }
                            }
                        }
                    }

                    if (unknownKeys.isNotEmpty()) {
                        item(key = "group_unknown") { RolithaxSectionHeader(title = "未收录配置项") }
                        unknownKeys.forEach { key ->
                            item(key = "unknown_$key") {
                                PropCard(
                                    prop = PropSchema(
                                        key = key,
                                        label = key,
                                        desc = "未收录的配置项",
                                        type = detectType(values[key].orEmpty()),
                                        group = "未收录配置项",
                                    ),
                                    value = values[key].orEmpty(),
                                    onValueChange = { v -> viewModel.setValue(key, v) },
                                )
                            }
                        }
                    }

                    if (values.isEmpty() && !state.missing) {
                        item(key = "empty") {
                            RolithaxEmptyState(
                                icon = Icons.Filled.Info,
                                title = "没有可编辑的配置项",
                                description = "文件已读取，但其中没有解析到任何键值对",
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun detectType(v: String): PropType = when {
    v == "true" || v == "false" -> PropType.BOOLEAN
    v.isNotBlank() && v.toDoubleOrNull() != null -> PropType.NUMBER
    else -> PropType.STRING
}

@Composable
private fun PropCard(
    prop: PropSchema,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val checked = value == "true"
    RolithaxCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            if (prop.type == PropType.BOOLEAN) {
                // 整行可切换：开关自身不再单独响应点击，避免重复语义
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = checked,
                            role = Role.Switch,
                            onValueChange = { onValueChange(it.toString()) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PropLabel(prop, Modifier.weight(1f))
                    Switch(checked = checked, onCheckedChange = null)
                }
            } else {
                PropLabel(prop, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                when (prop.type) {
                    PropType.SELECT -> SelectDropdown(prop.options, value, onValueChange)

                    // detectType 允许小数，因此用 Decimal 键盘（仍可输入整数）
                    PropType.NUMBER -> OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    PropType.STRING -> OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    PropType.BOOLEAN -> Unit
                }
            }
        }
    }
}

@Composable
private fun PropLabel(prop: PropSchema, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = prop.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (prop.desc.isNotBlank()) {
            Text(
                text = prop.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = prop.key,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectDropdown(
    options: List<PropOption>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.value == selected }?.label ?: selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}
