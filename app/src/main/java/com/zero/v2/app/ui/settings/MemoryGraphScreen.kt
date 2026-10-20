package com.zero.v2.app.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.data.dao.UserGraphDao
import com.zero.v2.core.data.entity.EntityType
import com.zero.v2.core.data.entity.UserGraphAttribute
import com.zero.v2.core.data.entity.UserGraphEntity
import com.zero.v2.core.data.entity.UserGraphRelationship
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.minTouchTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** 图谱 UI 状态 */
data class GraphUiState(
    val entities: List<UserGraphEntity> = emptyList(),
    val attrsByEntity: Map<String, List<UserGraphAttribute>> = emptyMap(),
    val relationships: List<UserGraphRelationship> = emptyList(),
    val loading: Boolean = true,
    val readError: String? = null
)

/** 图谱数据加载 — Step 4.12（实体量级小，逐实体查当前属性即可） */
@HiltViewModel
class MemoryGraphViewModel @Inject constructor(
    private val graphDao: UserGraphDao,
    private val traceManager: AgentTraceManager
) : ViewModel() {

    private val _state = MutableStateFlow(GraphUiState())
    val state: StateFlow<GraphUiState> = _state.asStateFlow()
    private var readJob: Job? = null

    init { load() }

    fun load() {
        if (readJob?.isActive == true) return
        _state.update { it.copy(loading = true, readError = null) }
        readJob = viewModelScope.launch {
            try {
                val entities = graphDao.getAllEntities()
                val attrs = mutableMapOf<String, List<UserGraphAttribute>>()
                val rels = mutableListOf<UserGraphRelationship>()
                entities.forEach { e ->
                    attrs[e.id] = graphDao.getCurrentAttributes(e.id)
                    rels.addAll(graphDao.getCurrentRelationships(e.id))
                }
                _state.value = GraphUiState(entities, attrs, rels, loading = false)
                traceManager.record("ui_graph", NodeType.SYSTEM_EVENT, "memory_ui",
                    "graph_loaded: entities=${entities.size} " +
                            "attributes=${attrs.values.sumOf { it.size }} relationships=${rels.size}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(loading = false,
                    readError = "无法读取图谱，请重试；已加载的内容仍保留") }
            }
        }
    }
}

/**
 * 用户图谱可视化 — Step 4.12（DESIGN 允许"力导向图或层级列表"，本 Step 取层级列表；
 * 力导向图留打磨期）。实体卡片可展开: 当前属性（版本化知识）+ 关系边。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryGraphScreen(
    onBack: () -> Unit,
    viewModel: MemoryGraphViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    MemoryGraphContent(state, onBack, viewModel::load)
}

@Composable
internal fun MemoryGraphContent(state: GraphUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    val entityNames = remember(state.entities) { state.entities.associate { it.id to it.name } }
    val relationshipsByEntity = remember(state.relationships) { state.relationships.groupBy { it.fromEntityId } }
    val viewport = rememberSaveable(saver = GraphViewport.Saver) { GraphViewport(LazyListState()) }
    val listState = viewport.list
    var expandedIds by rememberSaveable { mutableStateOf(listOf("user")) }
    LaunchedEffect(viewport, state.entities.isNotEmpty()) {
        val target = viewport.pendingRestore
        if (target != null && state.entities.isNotEmpty()) {
            // The first restored measure can clamp the offset during initial viewport layout.
            // Replay once after layout; a fresh user scroll takes priority.
            withFrameNanos { }
            withFrameNanos { }
            if (!listState.isScrollInProgress) {
                listState.scrollToItem(target.first.coerceAtMost(state.entities.lastIndex), target.second)
            }
            viewport.pendingRestore = null
        }
    }

    Scaffold(
        topBar = {
            ZeroPageHeader("用户图谱", onBack)
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.readError?.let { error ->
                ZeroCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = onRetry, enabled = !state.loading,
                            modifier = Modifier.minTouchTarget()) { Text("重新读取") }
                    }
                }
            }
            if (state.loading && state.entities.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.entities.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.readError != null) "读取未完成" else "暂无图谱数据\n积累记忆后将自动提取实体与关系",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.entities, key = { it.id }) { entity ->
                        GraphEntityCard(
                            entity = entity,
                            attributes = state.attrsByEntity[entity.id].orEmpty(),
                            relationships = relationshipsByEntity[entity.id].orEmpty(),
                            entityNames = entityNames,
                            expanded = entity.id in expandedIds,
                            onToggle = {
                                expandedIds = if (entity.id in expandedIds) expandedIds - entity.id else expandedIds + entity.id
                            }
                        )
                    }
                }
            }
        }
    }
}

private class GraphViewport(val list: LazyListState, var pendingRestore: Pair<Int, Int>? = null) {
    companion object {
        val Saver = Saver<GraphViewport, List<Int>>(
            save = { listOf(it.list.firstVisibleItemIndex, it.list.firstVisibleItemScrollOffset) },
            restore = { GraphViewport(LazyListState(it[0], it[1]), it[0] to it[1]) }
        )
    }
}

@Composable
private fun GraphEntityCard(
    entity: UserGraphEntity,
    attributes: List<UserGraphAttribute>,
    relationships: List<UserGraphRelationship>,
    entityNames: Map<String, String>,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    ZeroCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().minTouchTarget()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起实体" else "展开实体", onClick = onToggle)
                .clearAndSetSemantics {
                    text = AnnotatedString(entity.name)
                    role = Role.Button
                    stateDescription = "${entityLabel(entity.type)}，${if (expanded) "已展开" else "已收起"}"
                    onClick(if (expanded) "收起实体" else "展开实体") { onToggle(); true }
                },
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entity.name, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("${entityLabel(entity.type)} · ${if (expanded) "收起" else "展开"}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(if (entity.memoryDerived) "记忆提取实体" else "历史实体 · 来源未核验",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                if (attributes.isEmpty() && relationships.isEmpty()) {
                    Text("暂无当前属性与关系", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                attributes.forEach { attr ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(attr.key, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(attr.value, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text(if (attr.evidence?.startsWith("memory:v1:") == true) "来源可追溯" else "来源未核验",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                relationships.forEach { rel ->
                    val provenance = if (rel.evidence?.startsWith("memory:v1:") == true) "来源可追溯" else "来源未核验"
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${rel.type} · ${entityNames[rel.toEntityId] ?: rel.toEntityId}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("自 ${formatDate(rel.effectiveFrom)} · $provenance",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun entityLabel(type: EntityType): String = when (type) {
    EntityType.PERSON -> "人物"
    EntityType.PLACE -> "地点"
    EntityType.ORGANIZATION -> "组织"
    EntityType.SKILL -> "技能"
    EntityType.INTEREST -> "兴趣"
    EntityType.PROJECT -> "项目"
    EntityType.EVENT -> "事件"
    EntityType.CONCEPT -> "概念"
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMs))
