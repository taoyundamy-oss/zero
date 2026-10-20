package com.zero.v2.app.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp

/** Shared navigation row for library, workspace and settings; grows with large fonts. */
@Composable
fun ZeroNavigationRow(title: String, description: String,
    icon: ImageVector? = null, containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .clearAndSetSemantics {
            role = Role.Button
            contentDescription = "$title，$description"
            onClick { onClick(); true }
        },
        shape = ZeroShapes.card, color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.heightIn(min = 72.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One neutral container per section; rows own clicks and grow with the text. */
@Composable
fun ZeroNavigationGroup(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = ZeroShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column { content() }
    }
}

@Composable
fun ZeroNavigationDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant)
}
