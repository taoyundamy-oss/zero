package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.services.embedding.VectorDomain

@Composable
fun VectorMaintenanceScreen(onBack: () -> Unit, viewModel: VectorMaintenanceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    VectorMaintenanceContent(state, onBack, viewModel::refresh, viewModel::rollback)
}

@Composable
internal fun VectorMaintenanceContent(state: VectorMaintenanceState, onBack: () -> Unit, onRefresh: () -> Unit,
    onRollback: (VectorDomain, String) -> Unit) {
    var pending by remember { mutableStateOf<Pair<VectorDomain, String>?>(null) }
    Scaffold(topBar = { ZeroPageHeader("检索索引维护", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("查看索引状态，或回滚到直接前一版本。回滚会重新检查模型空间、来源与验收记录；不修改原始资料。")
                Text("本地默认迁移仍受质量与能耗验收限制。此页不发起嵌入请求、不自动构建索引或切换模型。")
                TextButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新索引状态") }
                if (state.busy) Text("正在处理，请等待")
                state.message?.let { Text(it) }
            }
            VectorDomain.entries.forEach { domain ->
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(domainLabel(domain), style = MaterialTheme.typography.titleMedium)
                            val row = state.rows[domain]
                            Text("活动版本：${row?.active ?: "无或尚未读取"}")
                            Text("待处理：${row?.backlog ?: "未知"} · 来源总数：${row?.total ?: "未知"}")
                            Text("候选状态：${row?.candidateState ?: "无或未知"}")
                            Text("模型空间：${when (row?.activeSpaceMatches) { true -> "匹配"; false -> "不匹配"; null -> "未知" }}")
                            Button(onClick = { row?.active?.let { pending = domain to it } },
                                enabled = !state.busy && row?.active != null && row.rollbackTarget != null) { Text("回滚${domainLabel(domain)}索引") }
                            if (row?.rollbackTarget == null) Text("暂无可回滚的前一版本")
                            Text("回滚要求当前所选模型匹配前一版本；是否可用由提交时校验决定。")
                        }
                    }
                }
            }
        }
    }
    pending?.let { (domain, version) ->
        AlertDialog(onDismissRequest = { if (!state.busy) pending = null }, title = { Text("确认回滚${domainLabel(domain)}索引？") },
            text = { Text("将从版本 $version 回滚到直接前一版本。当前版本或来源若已变化，操作会被拒绝；不会绕过质量门。") },
            confirmButton = { TextButton(onClick = { pending = null; onRollback(domain, version) }, enabled = !state.busy) { Text("确认回滚") } },
            dismissButton = { TextButton(onClick = { pending = null }, enabled = !state.busy) { Text("取消") } })
    }
}
