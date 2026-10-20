package com.zero.v2.app.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** One accessible toggle owner for the whole row; supporting text may wrap. */
@Composable
fun ZeroSettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ZeroSettingSwitch(title, description, checked, onCheckedChange, enabled = true)
}

@Composable
fun ZeroSettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
