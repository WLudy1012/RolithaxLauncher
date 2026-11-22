package com.wludy.rolithax.launcher.ui.connect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wludy.rolithax.launcher.shared.panels.PanelKind
import com.wludy.rolithax.launcher.shared.panels.PanelProfile
import com.wludy.rolithax.launcher.ui.WorkspaceBar
import com.wludy.rolithax.launcher.ui.panels.PanelEditor
import com.wludy.rolithax.launcher.ui.panels.PanelViewModel
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddConnectionScreen(onBack: () -> Unit, onPanelAdded: () -> Unit) {
    val names = listOf("MSLX Daemon") + PanelKind.entries.map { it.title }
    val icons = listOf(Icons.Default.Settings, Icons.Default.Build, Icons.AutoMirrored.Filled.List, Icons.Default.Share)
    val pager = rememberPagerState { names.size }
    val scope = rememberCoroutineScope()
    val model: PanelViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    var saveVersion by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.saveVersion) {
        if (saveVersion?.let { it != state.saveVersion } == true) {
            saveVersion = null
            onPanelAdded()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = { WorkspaceBar("添加连接", navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 72.dp),
                userScrollEnabled = !state.busy, pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) { page ->
                Surface(onClick = { scope.launch { pager.animateScrollToPage(page) } },
                    enabled = !state.busy, shape = MaterialTheme.shapes.large,
                    color = if (page == pager.currentPage) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(icons[page], null, Modifier.size(36.dp))
                        Text(names[page], style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                }
            }
            key(pager.settledPage) {
                if (pager.settledPage == 0) ConnectScreen(onConnected = onBack, autoConnect = false, embedded = true)
                else {
                    val kind = PanelKind.entries[pager.settledPage - 1]
                    val profile = remember(kind) { PanelProfile(UUID.randomUUID().toString(), "", kind, "", "") }
                    PanelEditor(profile, isNew = true, busy = state.busy || !state.ready, error = state.error,
                        onSave = { saveVersion = state.saveVersion; model.save(it) },
                        onClose = onBack, embedded = true)
                }
            }
        }
    }
}
