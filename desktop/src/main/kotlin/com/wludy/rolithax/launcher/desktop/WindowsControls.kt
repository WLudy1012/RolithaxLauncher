package com.wludy.rolithax.launcher.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.ui.unit.dp

@Composable
internal fun WindowsButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.heightIn(min = 36.dp), enabled, shape = RoundedCornerShape(5.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), content = content)
}

@Composable
internal fun WindowsOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier.heightIn(min = 36.dp), enabled, shape = RoundedCornerShape(5.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), content = content)
}

@Composable
internal fun WindowsTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp), content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 36.dp), enabled, shape = RoundedCornerShape(5.dp), contentPadding = contentPadding, content = content)
}

@Composable
internal fun WindowsSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    BasicTextField(value, onValueChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary), interactionSource = interaction,
        modifier = modifier.heightIn(min = 38.dp).background(colors.surfaceContainerLow, RoundedCornerShape(6.dp)).border(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.outline, RoundedCornerShape(6.dp)).semantics { contentDescription = placeholder },
        decorationBox = { field ->
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkspaceGlyph(WorkspaceIcon.Search)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    field()
                }
                if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(24.dp).semantics { contentDescription = "清空搜索" }) { WorkspaceGlyph(WorkspaceIcon.Close) }
            }
        },
    )
}
