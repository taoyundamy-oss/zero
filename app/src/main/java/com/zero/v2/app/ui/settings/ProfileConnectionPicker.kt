package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zero.v2.services.llm.LlmProfile

@Composable
internal fun ProfileConnectionPicker(
    connectionId: String,
    profiles: List<LlmProfile>,
    updateShared: Boolean,
    onShared: (Boolean) -> Unit,
    onSelect: (LlmProfile) -> Unit,
    onIndependent: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val references = profiles.filter { it.connectionId == connectionId }
    Column {
        Text("连接：${references.firstOrNull()?.displayName ?: "独立连接"}（${references.size} 个已保存模型引用）")
        TextButton(onClick = { expanded = true }) { Text("选择已有连接") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.distinctBy { it.connectionId }.forEach { profile ->
                DropdownMenuItem(text = { Text("${profile.displayName} · ${profile.baseUrl}") },
                    onClick = { onSelect(profile); expanded = false })
            }
        }
        TextButton(onClick = onIndependent) { Text("改用独立连接（需重新配置密钥）") }
        if (references.isNotEmpty()) {
            Text("共享此连接：${references.joinToString { it.displayName }}")
            Row {
                Checkbox(checked = updateShared, onCheckedChange = onShared)
                Text("保存时同时更新共享连接地址与认证")
            }
        }
    }
}
