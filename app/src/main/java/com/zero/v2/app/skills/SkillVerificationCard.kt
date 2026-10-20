package com.zero.v2.app.skills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.data.dao.KbDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltViewModel
class SkillVerificationViewModel @Inject constructor(
    private val store: SkillStore, private val kb: KbDao, private val bus: SystemBus,
) : ViewModel() {
    val documents = MutableStateFlow<List<SkillStore.Document>>(emptyList())
    val result = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    private val mutex = Mutex()
    init { refresh() }
    fun refresh() = work { documents.value = store.list() }
    fun confirm(document: SkillStore.Document, evidence: String) = work {
        store.confirmVerification(document.id, document.version, evidence)
        kb.deleteDoc(".zero-skills/${document.id}.md")
        bus.onFilesChanged.emit(listOf(SkillStore.PREFIX + "${document.id}.md"))
        documents.value = store.list()
        result.value = "已记录人工核验；修改内容后将重新变为候选。"
    }
    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                busy.value = true
                try { withContext(Dispatchers.IO) { block() } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (e: Exception) { result.value = e.message ?: "技能操作失败，请刷新后重试" }
                finally { busy.value = false }
            }
        }
    }
}

@Composable
fun SkillVerificationCard(viewModel: SkillVerificationViewModel = hiltViewModel()) {
    val documents by viewModel.documents.collectAsState()
    val result by viewModel.result.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<SkillStore.Document?>(null) }
    var evidence by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("技能核验", style = MaterialTheme.typography.titleMedium)
            Text("候选经验需实际验证。这里记录你的核验依据，不代表自动测试通过，也不增加工具权限。")
            OutlinedTextField(query, { query = it }, label = { Text("按 ID 或正文查找") })
            TextButton(onClick = { viewModel.refresh() }, enabled = !busy) { Text("刷新") }
            documents.filter { query.isBlank() || it.id.contains(query, true) || it.markdown.contains(query, true) }
                .take(20).forEach { document ->
                    TextButton(onClick = { selected = document; evidence = "" }, enabled = !busy) {
                        Text("${document.id} · ${if (document.status == "user_confirmed") "人工核验" else "候选"}")
                    }
                }
            if (documents.isEmpty()) Text("暂无技能；可在对话中使用技能工具创建候选经验。")
            result?.let { Text(it) }
        }
    }
    selected?.let { document ->
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text("核验 ${document.id}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer {
                        Text(document.markdown, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                    }
                    OutlinedTextField(evidence, { if (it.length <= 2000) evidence = it },
                        label = { Text("验证任务、结果及依据") }, maxLines = 5)
                    result?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && evidence.isNotBlank(), onClick = {
                    viewModel.confirm(document, evidence)
                    selected = null
                }) { Text("确认此版本验证通过") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { selected = null }) { Text("取消") } },
        )
    }
}
