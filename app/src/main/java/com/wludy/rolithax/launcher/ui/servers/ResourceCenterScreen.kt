package com.wludy.rolithax.launcher.ui.servers

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.SubcomposeAsyncImage
import com.wludy.rolithax.launcher.data.ManagedServer
import com.wludy.rolithax.launcher.data.resources.ResourceIssue
import com.wludy.rolithax.launcher.data.resources.ResourceProject
import com.wludy.rolithax.launcher.data.resources.ResourceVersion
import com.wludy.rolithax.launcher.data.resources.hasCompleteCompatibilityMetadata
import com.wludy.rolithax.launcher.data.resources.isCompatibleWith
import com.wludy.rolithax.launcher.ui.RolithaxCard
import com.wludy.rolithax.launcher.ui.RolithaxEmptyState
import com.wludy.rolithax.launcher.ui.RolithaxSectionHeader
import com.wludy.rolithax.launcher.ui.theme.GlassCardShape
import com.wludy.rolithax.launcher.ui.theme.GlassSurface
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch

private data class ResourceCategory(val id: String, val label: String)

private val resourceCategories = listOf(
    ResourceCategory("mod", "模组"),
    ResourceCategory("plugin", "插件"),
    ResourceCategory("modpack", "整合包"),
    ResourceCategory("datapack", "数据包"),
)

/** 资源卡片封面尺寸；所有占位/错误态必须复用同一尺寸，避免列表加载时高度跳动。 */
private val CoverSize = 56.dp
private val CoverShape = RoundedCornerShape(14.dp)
private val TagShape = RoundedCornerShape(8.dp)
private val FilterBarShape = RoundedCornerShape(18.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceCenterScreen(
    onOpenCreate: () -> Unit,
    viewModel: ResourceCenterViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val projects = viewModel.projects.collectAsLazyPagingItems()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        bottomBar = {
            when {
                state.installing -> InstallProgressBar(state.installProgress, state.installMessage, viewModel::cancelInstall)
                state.cart.isNotEmpty() -> CartBar(state.cart.size, viewModel::reviewCart)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .widthIn(max = 920.dp)
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("资源中心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "为目标实例挑选资源，统一检查依赖后再安装",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TargetSelector(
                    servers = state.servers,
                    selectedKey = state.selectedServerKey,
                    loading = state.loadingTargets,
                    gameVersion = state.gameVersion,
                    loader = state.loader,
                    onClick = viewModel::showTargetPicker,
                )

                if (state.cores.isNotEmpty()) MslApiBanner(state.cores.size, onOpenCreate)

                FilterBar(
                    query = state.query,
                    onQueryChange = viewModel::setSearch,
                    serverOnly = state.serverOnly,
                    onServerOnlyChange = viewModel::setServerOnly,
                    category = state.category,
                    onCategoryChange = viewModel::setCategory,
                )

                RolithaxSectionHeader(
                    title = when (state.category) {
                        "plugin" -> "适合此服务端的插件"
                        "modpack" -> "可以直接部署的整合包"
                        "datapack" -> "为世界添加数据包"
                        else -> "适合此服务端的模组"
                    },
                    subtitle = listOfNotNull(
                        state.gameVersion.takeIf(String::isNotBlank)?.let { "Minecraft $it" },
                        state.loader.takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercase)?.let { "适配 $it" },
                    ).joinToString(" · ").ifBlank { "未选定版本与加载器" },
                )

                val refreshState = projects.loadState.refresh
                val appendState = projects.loadState.append
                val listModifier = Modifier.fillMaxWidth().weight(1f)
                when {
                    refreshState is LoadState.Loading && projects.itemCount == 0 -> {
                        LoadingState("正在加载资源")
                    }
                    refreshState is LoadState.Error && projects.itemCount == 0 -> {
                        EmptyState(
                            title = "资源暂时无法加载",
                            description = refreshState.error.message ?: "检查网络后重试",
                            actionLabel = "重试",
                            onAction = projects::retry,
                        )
                    }
                    refreshState is LoadState.Error -> {
                        InlineRefreshError("刷新资源失败", refreshState.error.message ?: "检查网络后重试", projects::retry)
                    }
                    projects.itemCount == 0 && appendState is LoadState.Loading -> {
                        LoadingState("正在扫描兼容资源")
                    }
                    projects.itemCount == 0 && appendState is LoadState.NotLoading && !appendState.endOfPaginationReached -> {
                        EmptyState(
                            title = "正在查找兼容资源",
                            description = "首批扫描还没有找到匹配项目，远端仍有内容待检查。",
                            actionLabel = "继续扫描",
                            onAction = projects::refresh,
                        )
                    }
                    projects.itemCount == 0 -> {
                        EmptyState(
                            title = "没有匹配的资源",
                            description = "试试其他关键词、版本或资源类型。",
                            actionLabel = "清空搜索词",
                            onAction = { viewModel.setSearch("") },
                        )
                    }
                    else -> {
                        ProjectList(projects, viewModel, listModifier)
                        ListFooter(projects, refreshState, appendState)
                    }
                }
            }
        }
    }

    if (state.showTargetPicker) {
        ModalBottomSheet(onDismissRequest = viewModel::hideTargetPicker) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("选择安装目标", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("云端资源由 Daemon 直接下载，不会经过手机中转。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                when {
                    state.loadingTargets -> {
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.servers.isEmpty() -> EmptyState(
                        title = "还没有可用实例",
                        description = "先创建本地实例或连接 MSLX Daemon。",
                        actionLabel = "创建服务端",
                        onAction = onOpenCreate,
                    )
                    else -> LazyColumn {
                        items(state.servers, key = ManagedServer::key) { server ->
                            TargetRow(server, selected = server.key == state.selectedServerKey) { viewModel.selectServer(server) }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    state.selectedProject?.let { project ->
        val versionsError = state.versionsError
        ModalBottomSheet(onDismissRequest = viewModel::closeVersions) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("选择游戏版本与加载器匹配的文件", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                when {
                    state.loadingVersions -> {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    versionsError != null && state.versions.isEmpty() -> EmptyState(
                        title = "版本加载失败",
                        description = versionsError,
                        actionLabel = "重试",
                        onAction = viewModel::retryVersions,
                    )
                    state.versions.isEmpty() -> EmptyState(
                        title = "没有可安装的版本",
                        description = "当前实例的游戏版本或加载器可能不受支持。",
                    )
                    else -> LazyColumn {
                        versionsError?.let { error ->
                            item {
                                InlineRefreshError("版本刷新失败", error, viewModel::retryVersions)
                            }
                        }
                        items(state.versions, key = ResourceVersion::id) { version ->
                            VersionRow(version, state.gameVersion, state.loader) { viewModel.addVersion(version) }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (state.showCart) {
        ModalBottomSheet(onDismissRequest = viewModel::closeCart) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("安装清单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    state.selectedServerKey?.let { key -> state.servers.firstOrNull { it.key == key }?.name }.orEmpty().ifBlank { "请先选择目标实例" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (state.cart.isEmpty()) {
                    EmptyState(
                        title = "清单还是空的",
                        description = "选好资源版本后，它们会在这里一起校验。",
                    )
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(state.cart, key = { it.project.id }) { entry ->
                            CartRow(entry.project, entry.version.number) { viewModel.removeFromCart(entry.providerId, entry.project.id) }
                        }
                    }
                    val sharedMods = state.cart.filter { it.project.type == "mod" && it.project.clientCompatible }
                    if (sharedMods.isNotEmpty()) {
                        GlassSurface(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = FilterBarShape,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "客户端需同步 ${sharedMods.size} 个模组",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    TextButton(onClick = {
                                        val text = sharedMods.joinToString("\n") { "${it.project.name} ${it.version.number}".trim() }
                                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                            ClipData.newPlainText("客户端同步模组", text),
                                        )
                                        scope.launch { snackbar.showSnackbar("客户端模组清单已复制") }
                                    }) { Text("复制清单") }
                                }
                                Text(
                                    sharedMods.joinToString("、") { it.project.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    state.issues.forEach { issue -> IssueRow(issue) }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = viewModel::closeCart, modifier = Modifier.weight(1f).height(50.dp)) {
                            Text("继续挑选")
                        }
                        Button(
                            onClick = viewModel::installCart,
                            enabled = state.cartValidated && !state.validatingCart && !state.installing && state.issues.none { it.severity == ResourceIssue.Severity.BLOCKING },
                            modifier = Modifier.weight(1f).height(50.dp),
                        ) {
                            if (state.validatingCart) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("安装到目标")
                        }
                    }
                    TextButton(
                        onClick = viewModel::reviewCart,
                        enabled = !state.validatingCart,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(if (state.issues.isEmpty()) "检查依赖与冲突" else "重新检查") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ProjectList(projects: LazyPagingItems<ResourceProject>, viewModel: ResourceCenterViewModel, modifier: Modifier) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(projects.itemCount, key = projects.itemKey { it.id }) { index ->
            projects[index]?.let { ResourceRow(it) { viewModel.openVersions(it) } }
        }
    }
}

/** 列表尾部：分页加载中 / 加载失败 / 到底，并保留 Modrinth 数据署名。 */
@Composable
private fun ListFooter(
    projects: LazyPagingItems<ResourceProject>,
    refresh: LoadState,
    append: LoadState,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            refresh is LoadState.Loading -> ScanningHint("正在刷新资源")
            append is LoadState.Loading -> ScanningHint("正在继续扫描兼容资源")
            append is LoadState.Error -> TextButton(onClick = projects::retry) { Text("加载更多失败，点此重试") }
            refresh is LoadState.NotLoading && append is LoadState.NotLoading && append.endOfPaginationReached -> {
                Text(
                    "已显示 ${projects.itemCount} 项 · 没有更多了",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "资源数据来自 Modrinth API v3",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanningHint(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResourceRow(project: ResourceProject, onClick: () -> Unit) {
    RolithaxCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ProjectCover(project)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(project.author.takeIf(String::isNotBlank), "${compactCount(project.downloads)} 次下载").joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    project.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ResourceTags(project)
            }
        }
    }
}

/** 封面：统一的尺寸/圆角/裁剪，缺失或失败时退化为首字母占位。 */
@Composable
private fun ProjectCover(project: ResourceProject) {
    val fallback: @Composable () -> Unit = { CoverFallback(project.name) }
    val url = project.iconUrl?.takeIf(String::isNotBlank)
    if (url == null) {
        fallback()
    } else {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = "${project.name} 图标",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(CoverSize).clip(CoverShape),
            loading = { fallback() },
            error = { fallback() },
        )
    }
}

@Composable
private fun CoverFallback(name: String) {
    Box(
        modifier = Modifier
            .size(CoverSize)
            .clip(CoverShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).ifBlank { "?" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun ResourceTags(project: ResourceProject) {
    val loaders = project.loaders.take(2)
    val versions = project.gameVersions.take(2)
    if (loaders.isEmpty() && versions.isEmpty() && project.serverCompatible) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 1.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        loaders.forEach { ResourceTag(it.replaceFirstChar(Char::uppercase)) }
        versions.forEach { ResourceTag(it) }
        if (!project.serverCompatible) ResourceTag("仅客户端")
    }
}

@Composable
private fun ResourceTag(text: String) {
    Surface(
        shape = TagShape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 搜索 + 筛选条：贴底固定，保留输入控件语义，只把容器换成玻璃面板。 */
@Composable
private fun FilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    serverOnly: Boolean,
    onServerOnlyChange: (Boolean) -> Unit,
    category: String,
    onCategoryChange: (String) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = FilterBarShape) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("搜索模组、插件或整合包", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shape = RoundedCornerShape(12.dp),
                )
                FilterChip(
                    selected = serverOnly,
                    onClick = { onServerOnlyChange(!serverOnly) },
                    label = { Text("适配服务端") },
                    shape = TagShape,
                    leadingIcon = if (serverOnly) ({ Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp)) }) else null,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                resourceCategories.forEach { item ->
                    FilterChip(
                        selected = category == item.id,
                        onClick = { onCategoryChange(item.id) },
                        label = { Text(item.label) },
                        shape = TagShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        ),
                    )
                }
            }
        }
    }
}

/** 安装目标：整块玻璃卡可点，保留按钮高度的触控区。 */
@Composable
private fun TargetSelector(
    servers: List<ManagedServer>,
    selectedKey: String?,
    loading: Boolean,
    gameVersion: String,
    loader: String,
    onClick: () -> Unit,
) {
    val selected = servers.firstOrNull { it.key == selectedKey }
    RolithaxCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.AccountBox,
                contentDescription = null,
                tint = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        loading -> "正在读取实例"
                        selected == null -> "选择安装目标"
                        else -> "${selected.name} · ${selected.sourceLabel}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(gameVersion.takeIf(String::isNotBlank)?.let { "Minecraft $it" }, loader.takeIf(String::isNotBlank))
                        .joinToString(" · ").ifBlank { "本地实例或云端 Daemon" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "更换",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TargetRow(server: ManagedServer, selected: Boolean, onClick: () -> Unit) {
    RolithaxCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), highlighted = selected) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${server.sourceLabel} · ${server.detail.ifBlank { "Minecraft 服务端" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Icon(Icons.Filled.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun VersionRow(version: ResourceVersion, gameVersion: String, loader: String, onAdd: () -> Unit) {
    val matching = version.isCompatibleWith(gameVersion, loader)
    val metadataComplete = version.hasCompleteCompatibilityMetadata(gameVersion, loader)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                version.name.ifBlank { version.number },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(version.number, version.loaders.take(3).joinToString(), version.gameVersions.take(3).joinToString()).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onAdd, enabled = matching) {
            Text(
                when {
                    !matching -> "不匹配"
                    !metadataComplete -> "兼容性未知"
                    else -> "加入"
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CartRow(project: ResourceProject, version: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "版本 $version · ${typeLabel(project.type)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "移除 ${project.name}") }
    }
}

@Composable
private fun IssueRow(issue: ResourceIssue) {
    val color = when (issue.severity) {
        ResourceIssue.Severity.BLOCKING -> MaterialTheme.colorScheme.error
        ResourceIssue.Severity.WARNING -> MaterialTheme.colorScheme.tertiary
        ResourceIssue.Severity.INFO -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(issue.message, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
    }
}

/** 贴底购物车条：保留输入/操作条语义，圆角与内边距对齐玻璃面板。 */
@Composable
private fun CartBar(count: Int, onReview: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = FilterBarShape,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("$count 项待安装", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Button(onClick = onReview, modifier = Modifier.height(48.dp)) { Text("检查并安装") }
        }
    }
}

/** 贴底安装进度条：同 [CartBar] 的贴底风格。 */
@Composable
private fun InstallProgressBar(progress: Float, message: String, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = FilterBarShape,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.ifBlank { "正在安装资源" }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onCancel) { Text("取消") }
            }
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MslApiBanner(count: Int, onOpenCreate: () -> Unit) {
    RolithaxCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AccountBox,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("MSLAPI 服务端核心", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${count} 个核心可在新建流程中选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpenCreate) { Text("新建服务端") }
        }
    }
}

@Composable
private fun ColumnScope.EmptyState(title: String, description: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
        RolithaxEmptyState(
            icon = Icons.Filled.Info,
            title = title,
            description = description,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }
}

@Composable
private fun ColumnScope.LoadingState(text: String) {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator()
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InlineRefreshError(title: String, message: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = GlassCardShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

private fun compactCount(value: Long): String {
    val text = when {
        value >= 100_000_000 -> String.format(Locale.getDefault(), "%.1f亿", value / 100_000_000.0)
        value >= 10_000 -> String.format(Locale.getDefault(), "%.1f万", value / 10_000.0)
        value >= 1_000 -> String.format(Locale.getDefault(), "%.1f千", value / 1_000.0)
        else -> NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)
    }
    return text.removeSuffix(".0")
}

private fun typeLabel(type: String): String = when (type) {
    "mod" -> "模组"
    "plugin" -> "插件"
    "modpack" -> "整合包"
    "datapack" -> "数据包"
    else -> type
}
