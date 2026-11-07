package com.wludy.rolithax.launcher.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class TopPage(val label: String, val icon: ImageVector) {
    HOME("概览", Icons.Filled.Home),
    INSTANCES("实例", Icons.AutoMirrored.Filled.List),
    CONNECTIONS("连接", Icons.Filled.Share),
    RESOURCES("资源", Icons.Filled.Build),
    SETTINGS("设置", Icons.Filled.Settings),
}

@Composable
fun MainBottomNav(current: TopPage, onNavigate: (TopPage) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        TopPage.entries.forEach { page ->
            NavigationBarItem(
                selected = page == current,
                onClick = { onNavigate(page) },
                icon = { Icon(page.icon, contentDescription = null) },
                label = { NavigationLabel(page) },
            )
        }
    }
}

@Composable
fun MainNavigationRail(
    current: TopPage,
    onNavigate: (TopPage) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(TopPage.entries) { page ->
                NavigationRailItem(
                    selected = page == current,
                    onClick = { onNavigate(page) },
                    icon = { Icon(page.icon, contentDescription = null) },
                    label = { NavigationLabel(page) },
                    alwaysShowLabel = showLabels,
                )
            }
        }
    }
}

@Composable
private fun NavigationLabel(page: TopPage) {
    Text(page.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
