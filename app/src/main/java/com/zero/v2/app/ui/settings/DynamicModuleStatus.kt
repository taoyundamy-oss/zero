package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.minTouchTarget

/** Shared list/detail projection; failure is persistent until a retry or explicit dismissal. */
@Composable
internal fun DynamicModuleStatus(state: DynamicModulesUiState, onRetry: () -> Unit,
    onDismiss: () -> Unit, detail: Boolean = false) {
    val loading = if (detail) state.detailLoading else state.loading
    val error = if (detail) state.detailError else state.readError
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let {
        ZeroCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry, enabled = !loading, modifier = Modifier.minTouchTarget()) { Text("重新读取") }
            }
        }
    }
    val message = state.actionError ?: state.actionMessage
    message?.let {
        val failed = state.actionError != null
        ZeroCard(Modifier.fillMaxWidth(),
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(12.dp)) {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss, modifier = Modifier.minTouchTarget()) { Text("关闭提示") }
            }
        }
    }
}
