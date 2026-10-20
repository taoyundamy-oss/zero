package com.zero.v2.app.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DataExportScreen(onBack: () -> Unit, viewModel: DataExportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var pendingImport by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let(viewModel::exportTo)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        pendingImport = it?.toString()
    }
    BackupPage(state, onBack, {
        exportLauncher.launch("zero_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.json")
    }, { importLauncher.launch(arrayOf("application/json")) }, viewModel::cancel)
    pendingImport?.let { selected ->
        BackupRestoreConfirmation({ pendingImport = null }) {
            pendingImport = null
            viewModel.importFrom(Uri.parse(selected))
        }
    }
}

/** Production presentation shared with isolated acceptance; the SAF chooser stays in the route. */
@Composable
internal fun BackupPage(state: DataExportState, onBack: () -> Unit, export: () -> Unit,
    selectImport: () -> Unit, cancel: () -> Unit) {
    var blockedBack by remember { mutableStateOf(false) }
    val back = { if (state.busy) blockedBack = true else onBack() }
    BackHandler(onBack = back)
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { ZeroPageHeader("数据备份", back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("保存与恢复", style = MaterialTheme.typography.titleLarge)
            Text("备份对话、消息、角色、记忆和活跃或暂停的自动化任务。",
                style = MaterialTheme.typography.bodyLarge)
            BackupCard("备份内容", "不包含附件实体文件、知识库、API 密钥、模块配置、图谱和诊断日志。记忆向量不导出。") {
                Button(export, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("导出备份") }
            }
            BackupCard("恢复数据", "同 ID 记忆和复核记录保留本机状态；其他记录合并，新增追加。已删除记忆遵守删除记录，不因旧备份复活。") {
                OutlinedButton(selectImport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("选择备份文件")
                }
            }
            if (state.busy) BackupCard("正在处理", state.progress?.label ?: "正在准备") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.progress?.cancellable != false) TextButton(cancel) { Text("取消操作") }
            }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge) }
            state.lastExport?.let { BackupCard("导出完成", "对话 ${it.conversations} · 消息 ${it.messages} · 角色 ${it.characters} · " +
                "记忆 ${it.memories} · 复核 ${it.memoryReviews} · 自动化 ${it.automations}") {} }
            state.lastImport?.let { BackupCard("恢复完成", "对话 ${it.conversations} · 消息 ${it.messages} · 角色 ${it.characters} · " +
                "记忆 ${it.memories} · 复核 ${it.memoryReviews} · 自动化 ${it.automations} · 跳过 ${it.skipped} 条无效、已删除或本机已存在记录") {} }
            Text("文件大小上限为 32 MiB。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("文件提供方写入失败可能留下不完整文件，请重新导出。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("恢复不包含完整设备配置；记忆语义向量需等待可用的嵌入服务回填。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (blockedBack) AlertDialog(onDismissRequest = { blockedBack = false }, title = { Text("备份正在处理") },
        text = { Text(if (state.progress?.cancellable != false) "请先完成或取消当前操作，再离开页面。"
            else "文件写入或数据库恢复已经开始，请等待处理完成。") },
        confirmButton = { TextButton({ blockedBack = false }) { Text("留在页面") } })
}

@Composable
private fun BackupCard(title: String, body: String, actions: @Composable () -> Unit) {
    ZeroCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
    }
}

@Composable
internal fun BackupRestoreConfirmation(dismiss: () -> Unit, restore: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("恢复这个备份？") },
        text = { Text("同 ID 记忆、复核记录和复核来源消息保留本机状态；其他同 ID 数据会被备份覆盖。本机独有数据保留。建议先导出当前数据；密钥、模块配置和附件文件不会恢复。") },
        confirmButton = { TextButton(restore) { Text("确认恢复") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } })
}
