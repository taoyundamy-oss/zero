package com.zero.v2.app.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.core.data.entity.CharacterEntity
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.framework.chat.StateEventItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 角色管理页面 — Zero v2。
 *
 * 展示预设和用户自定义角色列表，支持新建、编辑、删除、设为当前。
 *
 * v3.6 修复: 对话框显式三态（Hidden/Creating/Editing）— 修复"进页面闪现创建界面"与
 *           "点新建无响应"（旧实现 null 同时表示新建模式与无对话框）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterManageScreen(
    viewModel: CharacterManageViewModel = hiltViewModel(),
    onBack: (() -> Unit)? = null
) {
    val characters by viewModel.characters.collectAsState()
    val editState by viewModel.editState.collectAsState()
    val stateUi by viewModel.stateUi.collectAsState()
    val actions by viewModel.actionState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.insertPresetsIfEmpty()
    }

    CharacterManageContent(characters, editState, stateUi, actions, onBack, CharacterPageActions(
        viewModel::startCreate, viewModel::startEdit, viewModel::cancelEdit, viewModel::saveCharacter,
        viewModel::setActive, viewModel::deleteCharacter, viewModel::setDynamicStyleEnabled,
        viewModel::resetAffinity, viewModel::resetMood, viewModel::reloadCharacters, viewModel::dismissFeedback))
}

internal data class CharacterPageActions(
    val create: () -> Unit, val edit: (CharacterEntity) -> Unit, val cancel: () -> Unit,
    val save: (String, String?, String) -> Unit, val activate: (String) -> Unit,
    val delete: (CharacterEntity) -> Unit, val style: (Boolean) -> Unit,
    val affinity: () -> Unit, val mood: () -> Unit, val reload: () -> Unit, val dismiss: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CharacterManageContent(characters: List<CharacterEntity>, editState: CharacterEditState,
    stateUi: CharacterStateUiState, actions: CharacterActionState, onBack: (() -> Unit)?, commands: CharacterPageActions) {
    val feedback = remember { SnackbarHostState() }
    LaunchedEffect(actions.error, actions.message, actions.readError) {
        (actions.error ?: actions.readError ?: actions.message)?.let { feedback.showSnackbar(it) }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(feedback) },
        topBar = { ZeroPageHeader("角色与表达", onBack) },
    ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "create") {
                    Button(onClick = commands.create, enabled = !actions.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("新建角色")
                    }
                }
                item(key = "read_status") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (actions.loading) Text("正在读取角色…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        actions.readError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = commands.reload, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取角色") }
                        }
                        actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (characters.isEmpty() && !actions.loading && actions.readError == null)
                            Text("暂无角色，创建一个角色开始对话。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // P3：动态角色表达区（开关 + 活跃角色状态查看 + 分别重置）
                item(key = "dynamic_expression_header") {
                    DynamicExpressionSection(
                        state = stateUi,
                        onToggle = commands.style,
                        onResetAffinity = commands.affinity,
                        onResetMood = commands.mood,
                        busy = actions.busy,
                    )
                }
                items(characters, key = { it.id }) { character ->
                    CharacterCard(
                        character = character,
                        isActive = character.isActive,
                        onClick = { commands.edit(character) },
                        onSetActive = { commands.activate(character.id) },
                        onDelete = if (!character.isPreset) {
                            { commands.delete(character) }
                        } else null,
                        busy = actions.busy,
                    )
                }
            }
    }

    when (val s = editState) {
        is CharacterEditState.Editing -> CharacterEditDialog(
            character = s.character,
            onSave = { name, desc, prompt -> commands.save(name, desc, prompt) },
            onCancel = { commands.cancel() },
            title = "编辑角色",
            busy = actions.busy,
            error = actions.error,
        )
        CharacterEditState.Creating -> CharacterEditDialog(
            character = null,
            onSave = { name, desc, prompt -> commands.save(name, desc, prompt) },
            onCancel = { commands.cancel() },
            title = "新建角色",
            busy = actions.busy,
            error = actions.error,
        )
        CharacterEditState.Hidden -> {}
    }
}

@Composable
private fun CharacterCard(
    character: CharacterEntity,
    isActive: Boolean,
    onClick: () -> Unit,
    onSetActive: () -> Unit,
    onDelete: (() -> Unit)?,
    busy: Boolean,
) {
    ZeroCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = character.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(listOfNotNull(if (isActive) "当前角色" else null, if (character.isPreset) "预设角色" else "自定义角色").joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!character.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = character.description!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            OutlinedButton(onClick = onSetActive, enabled = !busy && !isActive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "使用角色 ${character.id}" }) {
                Text(if (isActive) "正在使用" else "使用此角色")
            }
            OutlinedButton(onClick = onClick, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "编辑角色 ${character.id}" }) {
                Icon(Icons.Filled.Edit, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("编辑角色")
            }
            if (onDelete != null) {
                TextButton(onClick = onDelete, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "删除角色 ${character.id}" }) {
                    Icon(Icons.Filled.Delete, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("删除角色")
                }
            }
        }
    }
}

/**
 * 动态角色表达区（P3 / 设计§6）——角色页查看当前状态 + 最近变化依据 + 关闭动态表达 + 分别重置。
 *
 * - 开关：关闭动态表达（关闭不删除存量状态，固定人设继续回复）。
 * - 状态：活跃角色当前亲密度档位 + 累计交互数（不暴露内部计分/数值给 LLM，仅 UI 展示）。
 * - 依据：最近 5 条变化事件（亲密度/情绪 + 原因 + 时间）。
 * - 重置：分别重置亲密度（按角色）/ 情绪（按角色所有会话）。
 */
@Composable
private fun DynamicExpressionSection(
    state: CharacterStateUiState,
    onToggle: (Boolean) -> Unit,
    onResetAffinity: () -> Unit,
    onResetMood: () -> Unit,
    busy: Boolean,
) {
    var eventsExpanded by rememberSaveable(state.activeCharacterId) { mutableStateOf(false) }
    ZeroCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "动态角色表达",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "让表达随互动自然变化，关闭后保持固定风格。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.dynamicStyleEnabled, onCheckedChange = onToggle, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "动态角色表达" })
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(10.dp))

            val activeName = state.activeCharacterName
            if (activeName == null) {
                Text(
                    "未选择活跃角色",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "当前角色：$activeName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state.error ?: if (!state.loaded) "正在读取角色状态…"
                    else "亲密度：${state.affinityLabel} · 已记录升阶 ${state.interactionCount} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CharacterStateExplanation(state)
                if (state.recentEvents.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { eventsExpanded = !eventsExpanded },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (eventsExpanded) "收起变化依据" else "查看变化依据")
                    }
                    if (eventsExpanded) state.recentEvents.forEach { ev -> StateEventRow(ev) }
                }

                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onResetAffinity,
                        enabled = !busy && state.loaded && state.error == null && state.activeCharacterId != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重置亲密度")
                    }
                    OutlinedButton(onClick = onResetMood,
                        enabled = !busy && state.loaded && state.error == null && state.activeCharacterId != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重置情绪")
                    }
                }
            }
        }
    }
}

@Composable
private fun StateEventRow(ev: StateEventItem) {
    val label = when (ev.changeType) {
        "AFFINITY" -> "亲密度"
        "MOOD" -> "情绪"
        else -> ev.changeType
    }
    val time = remember(ev.createdAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ev.createdAt))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            ev.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 角色编辑/新建对话框。
 */
@Composable
private fun CharacterEditDialog(
    character: CharacterEntity?,
    onSave: (String, String?, String) -> Unit,
    onCancel: () -> Unit,
    title: String,
    busy: Boolean,
    error: String?,
) {
    var name by rememberSaveable(character?.id) { mutableStateOf(character?.name ?: "") }
    var description by rememberSaveable(character?.id) { mutableStateOf(character?.description ?: "") }
    var systemPrompt by rememberSaveable(character?.id) { mutableStateOf(character?.systemPrompt ?: "") }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("角色名称") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "角色名称草稿" }
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（可选）") },
                    enabled = !busy,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "角色描述草稿" }
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("系统提示词") },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "角色说明草稿" }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, description.ifBlank { null }, systemPrompt) },
                enabled = !busy && name.isNotBlank() && systemPrompt.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (busy) "正在保存…" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        }
    )
}
