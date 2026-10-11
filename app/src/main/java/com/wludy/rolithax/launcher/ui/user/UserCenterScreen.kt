package com.wludy.rolithax.launcher.ui.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import com.wludy.rolithax.launcher.data.model.UserInfo
import com.wludy.rolithax.launcher.ui.RolithaxCard
import com.wludy.rolithax.launcher.ui.RolithaxEmptyState
import com.wludy.rolithax.launcher.ui.RolithaxSectionHeader
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UserCenterScreen(
    onBack: () -> Unit,
    viewModel: UserCenterViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var revealApiKey by remember(state.user?.id) { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var showSelfEditor by remember { mutableStateOf(false) }
    var showCreateEditor by remember { mutableStateOf(false) }
    var editingUser by remember { mutableStateOf<UserInfo?>(null) }
    var deletingUser by remember { mutableStateOf<UserInfo?>(null) }

    LaunchedEffect(revealApiKey, state.user?.id) {
        if (revealApiKey) {
            delay(15_000)
            revealApiKey = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UserAvatar(user = state.user, size = 30)
                        Text("用户中心", modifier = Modifier.padding(start = 10.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::load) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        // 账号体系每台 Daemon 独立（去主连接）：多台时先选服务端，再看它的账号与资源
        val showSelector = state.daemons.size > 1
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (showSelector) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.daemons.forEach { daemon ->
                        FilterChip(
                            selected = daemon.id == state.selectedDaemonId,
                            onClick = { viewModel.selectDaemon(daemon.id) },
                            label = {
                                Text(
                                    text = daemon.name.ifBlank { daemon.id },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
            val contentModifier = Modifier.fillMaxSize()
            when {
                state.loading && state.user == null -> BoxLoading(contentModifier)
                state.user == null -> ErrorContent(
                    message = state.error ?: "未获取到用户信息",
                    modifier = contentModifier,
                    onRetry = viewModel::load,
                )
                else -> {
                    val user = state.user!!
                    val isSystemUser = user.isSystemUser
                    LazyColumn(
                        // 系统栏 insets 由此处兜底：Scaffold 关闭了内容 insets，列表底部需自行避开导航栏
                        modifier = contentModifier.navigationBarsPadding(),
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            ProfileCard(user = user, onEdit = { showSelfEditor = true }, canEdit = !isSystemUser)
                        }
                        item { RolithaxSectionHeader(title = "账号安全") }
                        item {
                            val clipboard = LocalClipboardManager.current
                            val apiKey = user.apiKey
                            RolithaxCard {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("API Key", style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        text = when {
                                            apiKey.isNullOrBlank() -> "未返回"
                                            revealApiKey -> apiKey
                                            else -> "••••••••"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(
                                            enabled = !apiKey.isNullOrBlank(),
                                            onClick = { revealApiKey = !revealApiKey },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text(if (revealApiKey) "隐藏" else "显示") }
                                        TextButton(
                                            enabled = !apiKey.isNullOrBlank(),
                                            onClick = { clipboard.setText(AnnotatedString(apiKey.orEmpty())) },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text("复制") }
                                    }
                                    Text(
                                        text = "完整密钥默认隐藏；显示后 15 秒自动隐藏，也可直接复制。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (user.role.equals("admin", ignoreCase = true) || isSystemUser) {
                            item {
                                RolithaxSectionHeader(
                                    title = "用户管理",
                                    actionLabel = "创建用户",
                                    onAction = { showCreateEditor = true },
                                )
                            }
                            if (state.users.isEmpty()) {
                                item {
                                    RolithaxEmptyState(
                                        icon = Icons.Filled.Person,
                                        title = "暂无其他用户",
                                        description = "当前只有一个账号，可在上方「创建用户」新增成员。",
                                    )
                                }
                            } else {
                                items(state.users, key = { it.id.orEmpty() }) { managedUser ->
                                    ManagedUserCard(
                                        user = managedUser,
                                        currentUserId = user.id,
                                        onEdit = { editingUser = managedUser },
                                        onDelete = { deletingUser = managedUser },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSelfEditor) {
        SelfEditorDialog(
            user = state.user!!,
            saving = state.saving,
            onDismiss = { showSelfEditor = false },
            onSave = { username, name, avatar, oldPassword, password, reset ->
                viewModel.updateSelf(username, name, avatar, oldPassword, password, reset)
                showSelfEditor = false
            },
        )
    }
    if (showCreateEditor) {
        AdminEditorDialog(
            title = "创建用户",
            user = null,
            resources = state.resources,
            saving = state.saving,
            onDismiss = { showCreateEditor = false },
            onSave = { username, password, name, role, selected, _ ->
                viewModel.createUser(username, password, name, role, selected)
                showCreateEditor = false
            },
        )
    }
    editingUser?.let { userToEdit ->
        AdminEditorDialog(
            title = "编辑用户",
            user = userToEdit,
            resources = state.resources,
            saving = state.saving,
            onDismiss = { editingUser = null },
            onSave = { _, password, name, role, selected, reset ->
                viewModel.updateUser(userToEdit.id.orEmpty(), name, password, role, reset, selected)
                editingUser = null
            },
        )
    }
    deletingUser?.let { userToDelete ->
        AlertDialog(
            onDismissRequest = { deletingUser = null },
            title = { Text("删除用户") },
            text = { Text("确定删除 ${userToDelete.name ?: userToDelete.username ?: "该用户"} 吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteUser(userToDelete.id.orEmpty())
                        deletingUser = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deletingUser = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun UserAvatar(
    user: UserInfo?,
    size: Int,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val dimen = size.dp
    val initial = (user?.name ?: user?.username ?: "?").take(1)
    val avatar = user?.avatar?.takeIf { it.isNotBlank() }
    if (avatar == null) {
        LetteredAvatar(initial, dimen, textStyle, containerColor, contentColor)
    } else {
        SubcomposeAsyncImage(
            model = avatar,
            contentDescription = "头像",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(dimen)
                .clip(CircleShape)
                .background(containerColor),
            loading = { LetteredAvatar(initial, dimen, textStyle, containerColor, contentColor) },
            error = { LetteredAvatar(initial, dimen, textStyle, containerColor, contentColor) },
        )
    }
}

@Composable
private fun LetteredAvatar(
    initial: String,
    dimen: Dp,
    textStyle: TextStyle,
    containerColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .size(dimen)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = textStyle,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
    }
}

@Composable
private fun ProfileCard(user: UserInfo, onEdit: () -> Unit, canEdit: Boolean) {
    RolithaxCard(
        highlighted = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UserAvatar(
                user = user,
                size = 84,
                textStyle = MaterialTheme.typography.headlineMedium,
                // 卡片底色本身是 primaryContainer 系，头像占位改用 surfaceVariant 才有对比
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = user.name ?: user.username ?: "未知用户",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "@${user.username ?: "unknown"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "角色：${if (user.isSystemUser) "System" else user.role ?: "user"}",
                style = MaterialTheme.typography.labelLarge,
                // 卡片是 primaryContainer 底：用 primary 作前景对比度不足，改用 onPrimaryContainer
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canEdit) {
                TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("编辑资料")
                }
            }
        }
    }
}

@Composable
private fun ManagedUserCard(user: UserInfo, currentUserId: String?, onEdit: () -> Unit, onDelete: () -> Unit) {
    RolithaxCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = user.name ?: user.username ?: "未知用户",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val roleLabel = if (user.isSystemUser) "System" else user.role ?: "user"
                Text(
                    text = "@${user.username ?: "unknown"} · $roleLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "资源：${user.resources.orEmpty().size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "编辑") }
            if (user.id != currentUserId) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SelfEditorDialog(
    user: UserInfo,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, Boolean) -> Unit,
) {
    var username by remember { mutableStateOf(user.username.orEmpty()) }
    var name by remember { mutableStateOf(user.name.orEmpty()) }
    var avatar by remember { mutableStateOf(user.avatar.orEmpty()) }
    var oldPassword by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var resetApiKey by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑资料") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 表单较高：限制高度并滚动，避免小屏上按钮被挤出屏幕
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true)
                OutlinedTextField(avatar, { avatar = it }, label = { Text("头像 URL") }, singleLine = true)
                OutlinedTextField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it },
                    label = { Text("当前密码（修改密码时必填）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = password.isNotBlank() && oldPassword.isBlank(),
                )
                OutlinedTextField(password, { password = it }, label = { Text("新密码（可选）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(resetApiKey, { resetApiKey = it })
                    Text("重置 API Key")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && (password.isBlank() || oldPassword.isNotBlank()),
                onClick = { onSave(username, name, avatar, oldPassword, password, resetApiKey) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminEditorDialog(
    title: String,
    user: UserInfo?,
    resources: List<ResourceOption>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, List<String>, Boolean) -> Unit,
) {
    var username by remember { mutableStateOf(user?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(user?.name.orEmpty()) }
    var role by remember { mutableStateOf(user?.role ?: "user") }
    var resetApiKey by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(user?.resources.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 资源权限列表可能很长：限制高度并滚动
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (user == null) OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true)
                if (user == null) OutlinedTextField(password, { password = it }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true)
                Text("角色", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("user" to "普通用户", "admin" to "管理员").forEach { (value, label) ->
                        FilterChip(selected = role == value, onClick = { role = value }, label = { Text(label) })
                    }
                }
                if (user != null) {
                    OutlinedTextField(password, { password = it }, label = { Text("新密码（可选）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(resetApiKey, { resetApiKey = it })
                        Text("重置 API Key")
                    }
                }
                if (resources.isNotEmpty() && role != "admin") {
                    Text("资源权限", style = MaterialTheme.typography.labelLarge)
                    resources.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(option.value in selected, { checked -> selected = if (checked) selected + option.value else selected - option.value })
                            Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = { onSave(username, password, name, role, selected.distinct(), resetApiKey) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        },
    )
}

@Composable
private fun BoxLoading(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun ErrorContent(message: String, modifier: Modifier, onRetry: () -> Unit) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        RolithaxEmptyState(
            icon = Icons.Filled.Warning,
            title = "加载失败",
            description = message,
            actionLabel = "重试",
            onAction = onRetry,
        )
    }
}
