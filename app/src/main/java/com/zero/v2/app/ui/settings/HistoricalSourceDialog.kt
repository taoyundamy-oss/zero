package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zero.v2.core.data.entity.MessageEntity

@Composable
internal fun HistoricalSourceDialog(state: MemoryReviewState, search: (String) -> Unit,
    retain: (MessageEntity) -> Unit, dismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<MessageEntity?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("迁移历史聊天原文") }, text = {
        Column {
            Text("仅本地查找明确用户来源。选中并确认后导入，不自动分类或改写旧记忆；未知来源需重新确认。")
            OutlinedTextField(query, { query = it }, label = { Text("原文关键词（空格分词）") })
            TextButton(onClick = { search(query) }, enabled = !state.busy && query.isNotBlank()) { Text("搜索历史原文") }
            state.notice?.let { Text(it) }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(state.historicalSources, key = { it.id }) { m ->
                    TextButton(onClick = { selected = m }, enabled = !state.busy) { Text(m.content.orEmpty(), maxLines = 4) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
    selected?.let { m ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("确认导入完整原文") }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) { item {
                Text("原记录时间：${java.util.Date(m.createdAt)}\n${m.content.orEmpty()}")
            } }
        }, confirmButton = { TextButton(onClick = { retain(m); selected = null }, enabled = !state.busy) { Text("导入来源库") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } })
    }
}
