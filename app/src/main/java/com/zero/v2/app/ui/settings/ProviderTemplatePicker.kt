package com.zero.v2.app.ui.settings

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zero.v2.services.llm.GatewayMode
import com.zero.v2.services.llm.ProviderTemplate
import com.zero.v2.services.llm.ProviderTemplates

@Composable
internal fun ProviderTemplatePicker(onSelect: (ProviderTemplate) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) { Text("从提供方模板创建独立连接") }
    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
        ProviderTemplates.all.forEach { template ->
            DropdownMenuItem(text = { Text(template.label) }, onClick = { onSelect(template); expanded = false })
        }
    }
    Text("模板创建独立连接，请补全地址与密钥。本地127.0.0.1指此手机。",
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun GatewayModePicker(mode: GatewayMode, onSelect: (GatewayMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) { Text("连接模式：${mode.label}") }
    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
        GatewayMode.entries.forEach { item ->
            DropdownMenuItem(text = { Text(item.label) }, onClick = { onSelect(item); expanded = false })
        }
    }
}
