package com.zero.v2.app.ui.settings

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.minTouchTarget
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.app.data.WorkDirRepository
import com.zero.v2.app.ui.chat.MessageBody
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.module.SystemEventModule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * 工作文件与记事本：SAF目录、原生Markdown阅读与外部打开。
 *
 * 工作目录：写入路径设置（SAF 选择器）+ 已有文件列表 + 应用外打开 + 删除；
 * 记事本：SystemEventModule 实时内容展示（LLM 写入即时刷新）。
 */
data class WorkFilesUiState(
    val dirConfigured: Boolean = false,
    val files: List<WorkDirRepository.WorkFileInfo> = emptyList(),
    val notepad: String = "",
    val busy: Boolean = false,
    val directoryPath: String = "",
    val documentName: String? = null,
    val documentText: String? = null,
    val error: String? = null,
)

@HiltViewModel
class WorkFilesViewModel @Inject constructor(
    private val workDir: WorkDirRepository,
    private val systemEvent: SystemEventModule,
    private val traceManager: AgentTraceManager
) : ViewModel() {

    private val _state = MutableStateFlow(WorkFilesUiState())
    val state: StateFlow<WorkFilesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            systemEvent.notepadState.collect { np -> _state.update { it.copy(notepad = np) } }
        }
        refresh()
    }

    fun refresh(path: String = _state.value.directoryPath) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val files = workDir.listFiles(path)
                _state.update { it.copy(dirConfigured = workDir.hasDirectory(), files = files,
                    directoryPath = path) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError("无法打开目录，请检查目录是否存在及访问授权；可重新选择工作目录。")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun open(file: WorkDirRepository.WorkFileInfo) {
        if (_state.value.busy) return
        val path = listOf(_state.value.directoryPath, file.name).filter { it.isNotEmpty() }.joinToString("/")
        if (file.isDirectory) {
            refresh(path)
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val bytes = workDir.readBinary(path, maxBytes = 512 * 1024)
                if (bytes == null) showError("无法阅读文件：请检查授权或文件是否超过 512 KB。")
                else _state.update { it.copy(documentName = file.name, documentText = bytes.toString(Charsets.UTF_8)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun closeDocument() { _state.update { it.copy(documentName = null, documentText = null) } }

    fun pickerInitialUri(): android.net.Uri? = workDir.currentDirUri()?.let { tree ->
        // DocumentsUI expects a document location, not the persisted tree grant URI.
        android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
        )
    }

    fun parentDirectory() { refresh(_state.value.directoryPath.substringBeforeLast('/', "")) }

    fun showError(message: String) { _state.update { it.copy(error = message) } }

    fun saveDirectory(uri: android.net.Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val ok = workDir.saveDirectory(uri)
            traceManager.record("ui_work_files", NodeType.SYSTEM_EVENT, "work_files",
                "dir_saved: ok=$ok")
            if (ok) refresh("") else showError("目录授权失败，请重新选择。")
        }
    }

    fun deleteFile(name: String) {
        viewModelScope.launch {
            val path = listOf(_state.value.directoryPath, name).filter { it.isNotEmpty() }.joinToString("/")
            val error = workDir.deleteFile(path)
            if (error != null) showError(error) else {
                traceManager.record("ui_work_files", NodeType.SYSTEM_EVENT, "work_files", "file_deleted: $name")
                refresh()
            }
        }
    }
}

@Composable
fun WorkFilesScreen(onBack: () -> Unit, viewModel: WorkFilesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), viewModel::saveDirectory)
    WorkFilesContent(state, onBack, { picker.launch(viewModel.pickerInitialUri()) }, { viewModel.refresh() },
        viewModel::parentDirectory, { file ->
            if (workFileDestination(file.name, file.isDirectory) != WorkFileDestination.EXTERNAL) viewModel.open(file)
            else {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.name.substringAfterLast('.', "")) ?: "*/*"
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { viewModel.showError("没有可打开此文件的应用，或文件授权已失效。") }
            }
        }, viewModel::deleteFile, viewModel::closeDocument)
}

/** Pure page projection: the ViewModel retains the single SAF/data owner. */
@Composable
fun WorkFilesContent(
    state: WorkFilesUiState, onBack: () -> Unit, onChooseDirectory: () -> Unit, onRefresh: () -> Unit,
    onParentDirectory: () -> Unit, onOpen: (WorkDirRepository.WorkFileInfo) -> Unit,
    onDelete: (String) -> Unit, onCloseDocument: () -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    BackHandler(state.documentName == null && state.directoryPath.isNotEmpty(), onBack = onParentDirectory)
    Scaffold(topBar = { ZeroPageHeader("工作文件", {
        if (state.directoryPath.isNotEmpty()) onParentDirectory() else onBack()
    }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ═══ 工作目录 ═══
            item {
                ZeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("工作目录", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (state.dirConfigured) "保存对话笔记与资料；点击文件可阅读或打开。"
                            else "选择一个目录，用于保存笔记、总结与其他工作文件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onChooseDirectory, enabled = !state.busy,
                            modifier = Modifier.minTouchTarget()) {
                            Text(if (state.dirConfigured) "更换目录" else "选择工作目录")
                        }
                    }
                }
            }

            // ═══ 文件列表 ═══
            item {
                Text("文件（${state.files.size}）", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                Text("工作目录/" + state.directoryPath, style = MaterialTheme.typography.bodySmall)
                Row {
                    if (state.directoryPath.isNotEmpty()) {
                        TextButton(onClick = onParentDirectory, enabled = !state.busy,
                            modifier = Modifier.minTouchTarget()) { Text("返回上级") }
                    }
                    TextButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.minTouchTarget()) { Text("刷新") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.files.isEmpty()) {
                item {
                    Text(
                        if (state.dirConfigured) "目录为空 — 对话中说「把…存到 xx.md」即可创建"
                        else "设置工作目录后显示文件列表",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.files, key = { it.uri.toString() }) { f ->
                ZeroCard(Modifier.fillMaxWidth().minTouchTarget().clickable(enabled = !state.busy) { onOpen(f) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (f.isDirectory) Icons.Outlined.Folder else Icons.Outlined.EditNote, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                            Text(
                                (if (f.isDirectory) "文件夹 · " else "${f.sizeBytes / 1024} KB · ") +
                                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(f.lastModified)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(enabled = !state.busy, onClick = { onOpen(f) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "打开 ${f.name}")
                        }
                        if (!f.isDirectory) IconButton(enabled = !state.busy, onClick = { deleteTarget = f.name }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除 ${f.name}",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // ═══ 记事本 ═══
            item {
                ZeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.EditNote, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("任务记事本",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        // Bounded viewport retains scrolling and long-press selection.
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                state.notepad.ifBlank { "（空）— 对话中说「记一下…」或「把…存进生词本」" },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        }
    }

    state.documentName?.let { name ->
        androidx.compose.ui.window.Dialog(onDismissRequest = onCloseDocument,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onCloseDocument, modifier = Modifier.minTouchTarget()) { Text("关闭") }
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
                        MessageBody(state.documentText.orEmpty(),
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { Text(it) }
                    }
                }
            }
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除文件") },
            text = { Text("确定删除 ${deleteTarget}？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(deleteTarget!!)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}
