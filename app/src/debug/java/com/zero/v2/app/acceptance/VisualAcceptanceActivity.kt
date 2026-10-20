package com.zero.v2.app.acceptance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.core.view.WindowCompat
import com.zero.v2.app.ui.navigation.AppDestinations
import com.zero.v2.app.ui.navigation.RootHubScreen
import com.zero.v2.app.ui.settings.SettingsScreen
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.app.ui.trace.AgentTraceTimeline
import com.zero.v2.core.trace.AgentTrace
import com.zero.v2.core.trace.NodeType

/** Non-exported debug fixture: real components, no models, mic, data writes or normal jobs. */
open class VisualAcceptanceActivity : ComponentActivity() {
    data class Fixture(val page: String = "settings", val dark: Boolean = false,
        val dynamic: Boolean = false, val fontScale: Float = 1f, val paneWidthDp: Int = 720)

    var fixture by mutableStateOf(Fixture())
    @Volatile var palette: ColorScheme? = null
    @Volatile var lastNavigation: String? = null
    @Volatile var retryExecutions: Int = 0
    var attachmentFixture by mutableStateOf<com.zero.v2.core.common.Attachment?>(null)
    @Volatile var attachmentRemovals: Int = 0
    val workActions = java.util.concurrent.CopyOnWriteArrayList<String>()
    val memoryActions = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun attachBaseContext(newBase: android.content.Context) {
        val scale = fontScaleForNextLaunch
        fontScaleForNextLaunch = null
        // Configure before the window/theme reads resources; keep the phone setting unchanged.
        super.attachBaseContext(newBase)
        if (scale != null) {
            require(scale == 1f || scale == 2f)
            applyOverrideConfiguration(android.content.res.Configuration().apply { fontScale = scale })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("acceptancePage")?.let { page ->
            fixture = Fixture(page, intent.getBooleanExtra("acceptanceDark", false),
                intent.getBooleanExtra("acceptanceDynamic", false),
                intent.getFloatExtra("acceptanceFontScale", 1f),
                intent.getIntExtra("acceptanceWidth", 360))
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Isolated profile fixtures contain no personal data; keep lock-screen state unchanged.
        if (intent.getStringExtra("acceptancePage").orEmpty().startsWith("profile-") &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fixture.fontScale)) {
                ZeroTheme(darkTheme = fixture.dark, dynamicColor = fixture.dynamic) {
                    val currentPalette = MaterialTheme.colorScheme
                    SideEffect {
                        palette = currentPalette
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !fixture.dark
                            isAppearanceLightNavigationBars = !fixture.dark
                        }
                    }
                    Box(Modifier.fillMaxSize().background(currentPalette.background), contentAlignment = Alignment.TopCenter) {
                    Surface(Modifier.widthIn(max = fixture.paneWidthDp.dp).fillMaxSize(), color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground) {
                        key(fixture) {
                            when (fixture.page) {
                                "usage" -> UsageFixture()
                                "library" -> RootHubScreen("资料", "知识、文件与长期记忆",
                                    AppDestinations.library, { lastNavigation = it })
                                "workspace" -> RootHubScreen("工作区", "任务、工具与运行记录",
                                    AppDestinations.workspace, { lastNavigation = it })
                                "attachment" -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                                    attachmentFixture?.let { attachment ->
                                        com.zero.v2.app.ui.chat.AttachmentTile(attachment) { attachmentRemovals++ }
                                    }
                                }
                                "graph-loading", "graph-error", "graph-stale", "graph", "graph-empty" -> GraphFixture()
                                "stop" -> StopFixture()
                                "chat" -> ChatFixture()
                                "markdown" -> MarkdownFixture()
                                "retry" -> RetryFixture()
                                "workfiles", "workfiles-empty", "workfiles-error", "workfiles-busy" -> WorkFilesFixture()
                                "memory", "memory-empty", "memory-delete-error", "memory-loading",
                                "memory-read-error", "memory-read-stale", "memory-source" -> MemoryFixture()
                                "events" -> Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    listOf("QQ_PRIVATE", "SELF_DRIVE", "NOTIFICATION").forEach { source ->
                                        com.zero.v2.app.ui.SystemEventCard(com.zero.v2.core.common.ChatMessage(
                                            id = source, role = com.zero.v2.core.common.MessageRole.SYSTEM,
                                            content = "只读事件资料已就绪。", systemSource = source, timestamp = 0))
                                    }
                                    com.zero.v2.app.ui.theme.ZeroCard {
                                        com.zero.v2.app.ui.theme.ZeroSettingSwitch("系统动态色", "使用壁纸配色；关闭后使用 Zero 中性色。", false, {})
                                    }
                                }
                                "trace" -> TraceFixture()
                                "profile-connections" -> Column(Modifier.fillMaxSize().padding(16.dp)
                                    .verticalScroll(rememberScrollState())) {
                                    val original = com.zero.v2.services.llm.LlmProfile.localQwen("http://127.0.0.1:8080/v1", "isolated")
                                    val peer = original.copy(id = "fixture-peer", connectionId = "fixture-shared",
                                        displayName = "共享验收连接", baseUrl = "https://fixture.example/v1", apiKeyRef = "fixture-ref")
                                    com.zero.v2.app.ui.settings.ProfileEditorPage(original,
                                        { "未配置" }, { lastNavigation = "unexpected-key-write" },
                                        { lastNavigation = "saved:${it.connectionId}:${it.apiKeyRef}" }, { lastNavigation = "back" },
                                        connections = listOf(peer))
                                }
                                "profile-editor" -> Column(Modifier.fillMaxSize().padding(16.dp)
                                    .verticalScroll(rememberScrollState())) {
                                    com.zero.v2.app.ui.settings.ProfileEditorPage(
                                        com.zero.v2.services.llm.LlmProfile.localQwen("http://127.0.0.1:8080/v1", "isolated"),
                                        { "未配置" }, { lastNavigation = "unexpected-key-write" },
                                        { lastNavigation = "saved-${it.maxTokens}" }, { lastNavigation = "back" })
                                }
                                else -> SettingsScreen { lastNavigation = it }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    @Composable
    private fun StopFixture() {
        val scope = rememberCoroutineScope()
        val task = remember { com.zero.v2.app.ui.chat.ChatGenerationTask(scope) }
        var active by remember { mutableStateOf(true) }
        var stopped by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { task.start { try { kotlinx.coroutines.awaitCancellation() } finally { active = false; stopped = task.userStopped } } }
        Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
            Text(if (stopped) "已停止回复" else "部分回复已保留", Modifier.padding(16.dp))
            com.zero.v2.app.ui.chat.ChatComposer("验收草稿", {}, false, !active, {}, {}, { lastNavigation = "send-after-stop" },
                isGenerating = active, onStop = { task.stop() })
        }
    }

    @Composable
    private fun ChatFixture() {
        var draft by remember { mutableStateOf("验收草稿") }
        Column(Modifier.fillMaxSize()) {
            com.zero.v2.app.ui.theme.ZeroPageHeader("Zero", actions = { TextButton({}) { Text("历史") } })
            Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.zero.v2.app.ui.MessageBubble(com.zero.v2.core.common.ChatMessage(id = "user", role = com.zero.v2.core.common.MessageRole.USER,
                    content = "请整理资料中的更正事实。"))
                for ((index, source) in com.zero.v2.core.common.MessageSource.entries.withIndex()) {
                    com.zero.v2.app.ui.MessageBubble(com.zero.v2.core.common.ChatMessage(id = "assistant-$index",
                        role = com.zero.v2.core.common.MessageRole.ASSISTANT, source = source,
                        content = "已核对资料来源，后续说明采用更正后的事实。",
                        thinking = if (index == 0) "核对已确认资料，保留来源。" else null))
                }
                com.zero.v2.app.ui.chat.ChatGenerationStatus(com.zero.v2.app.ui.GenerationState.ERROR)
            }
            com.zero.v2.app.ui.chat.ChatComposer(draft, { draft = it }, false, draft.isNotBlank(),
                { lastNavigation = "attach" }, { lastNavigation = "live" }, { lastNavigation = "send" })
        }
    }

    @Composable
    private fun GraphFixture() {
        val records = remember { listOf(com.zero.v2.core.data.entity.UserGraphEntity(
            "user", "合成图谱实体", com.zero.v2.core.data.entity.EntityType.PERSON, createdAt = 0)) +
            (1..12).map { com.zero.v2.core.data.entity.UserGraphEntity("synthetic-$it",
                if (it == 12) "图谱末行实体" else "合成实体 $it", com.zero.v2.core.data.entity.EntityType.CONCEPT, createdAt = 0) } }
        val attributes = remember { listOf(com.zero.v2.core.data.entity.UserGraphAttribute("user",
            "较长属性名称用于窄屏阅读", (1..12).joinToString("\n") { "第 $it 段合成属性用于长文阅读。" } + "\nGRAPH_ATTRIBUTE_END",
            com.zero.v2.core.data.entity.AttributeSource.USER_STATED, .9f, 0, evidence = "memory:v1:synthetic"),
            com.zero.v2.core.data.entity.UserGraphAttribute("user", "历史属性", "合成历史属性",
                com.zero.v2.core.data.entity.AttributeSource.INFERRED, .5f, 0)) }
        val relationships = remember { listOf(com.zero.v2.core.data.entity.UserGraphRelationship("user",
            "synthetic-12", "合成关系", 0, evidence = "memory:v1:synthetic"),
            com.zero.v2.core.data.entity.UserGraphRelationship("user", "synthetic-missing", "历史关系", 0)) }
        var state by remember { mutableStateOf(com.zero.v2.app.ui.settings.GraphUiState(
            entities = if (fixture.page in listOf("graph-stale", "graph")) records else emptyList(),
            attrsByEntity = if (fixture.page == "graph") mapOf("user" to attributes) else emptyMap(),
            relationships = if (fixture.page == "graph") relationships else emptyList(),
            loading = fixture.page == "graph-loading",
            readError = if (fixture.page in listOf("graph-error", "graph-stale")) "无法读取图谱，请重试；已加载的内容仍保留" else null)) }
        com.zero.v2.app.ui.settings.MemoryGraphContent(state,
            onBack = { memoryActions.add("back") },
            onRetry = { memoryActions.add("read-retry"); state = state.copy(entities = records, loading = false, readError = null) })
    }

    @Composable
    private fun MemoryFixture() {
        val records = remember {
            listOf(com.zero.v2.core.data.entity.MemoryEntity(id = "long", createdAt = 100,
                content = "长文记忆\n" + (1..45).joinToString("\n") { "第 $it 段合成记忆，只用于界面验收。" } + "\nMEMORY_DOCUMENT_END")) +
                (1..10).map { com.zero.v2.core.data.entity.MemoryEntity(id = "item-$it", content = "记忆记录 $it", createdAt = 90L - it) } +
                com.zero.v2.core.data.entity.MemoryEntity(id = "tail", content = "末行记忆", createdAt = 0,
                    type = com.zero.v2.core.common.MemoryType.PREFERENCE)
        }
        var state by remember { mutableStateOf(com.zero.v2.app.ui.settings.MemoryUiState(
            all = when (fixture.page) {
                "memory-empty", "memory-loading", "memory-read-error" -> emptyList()
                "memory-source" -> listOf(com.zero.v2.core.data.entity.MemoryEntity("user-fact:synthetic",
                    "已核验来源合成记录", type = com.zero.v2.core.common.MemoryType.FACT, sourceConversationId = "synthetic"),
                    com.zero.v2.core.data.entity.MemoryEntity("legacy-synthetic", "未核验来源合成记录"))
                else -> records
            },
            isLoading = fixture.page == "memory-loading",
            readError = if (fixture.page in listOf("memory-read-error", "memory-read-stale")) "无法读取记忆，请重试；已加载的内容仍保留" else null,
            deleteError = if (fixture.page == "memory-delete-error") "删除未完成，数据已保留，请重试" else null)) }
        com.zero.v2.app.ui.settings.MemoryManageContent(state,
            onBack = { memoryActions.add("back") },
            onQuery = { state = state.copy(query = it); memoryActions.add("query:$it") },
            onTypeFilter = { state = state.copy(typeFilter = it); memoryActions.add("filter:$it") },
            onDelete = { memoryActions.add("delete:$it") },
            onClearDeleteError = { state = state.copy(deleteError = null) },
            onRetryRead = { memoryActions.add("read-retry"); state = state.copy(all = records, isLoading = false, readError = null) })
    }

    @Composable
    private fun WorkFilesFixture() {
        fun entry(name: String, directory: Boolean = false) = com.zero.v2.app.data.WorkDirRepository.WorkFileInfo(
            name, android.net.Uri.parse("content://isolated-work-files/$name"), 2048, 0, directory)
        val root = remember { listOf(entry("资料", true)) + (1..10).map { entry("资料记录 $it.md") } + entry("末行资料.md") }
        var state by remember { mutableStateOf(com.zero.v2.app.ui.settings.WorkFilesUiState(
            dirConfigured = fixture.page != "workfiles-empty",
            files = if (fixture.page == "workfiles-empty") emptyList() else root,
            busy = fixture.page == "workfiles-busy", notepad = "本页验收笔记",
            error = if (fixture.page == "workfiles-error") "无法打开目录，请重新选择工作目录。" else null)) }
        com.zero.v2.app.ui.settings.WorkFilesContent(state,
            onBack = { workActions.add("back") }, onChooseDirectory = { workActions.add("choose") },
            onRefresh = { workActions.add("refresh") },
            onParentDirectory = { workActions.add("parent"); state = state.copy(directoryPath = "", files = root) },
            onOpen = { file ->
                workActions.add("open:${file.name}")
                state = if (file.isDirectory) state.copy(directoryPath = file.name, files = listOf(entry("验收.md")))
                else state.copy(documentName = file.name,
                    documentText = "# 工作资料\n\n来源：隔离合成资料。\n\n" +
                        (1..18).joinToString("\n\n") { "第 $it 段可直接阅读并长按选择。" } + "\n\nWORK_DOCUMENT_END")
            }, onDelete = { workActions.add("delete:$it") },
            onCloseDocument = { workActions.add("close"); state = state.copy(documentName = null, documentText = null) })
    }

    @Composable
    private fun RetryFixture() {
        var active by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
            com.zero.v2.app.ui.chat.ChatGenerationStatus(com.zero.v2.app.ui.GenerationState.ERROR,
                onRetry = { retryExecutions++; lastNavigation = "explicit-retry"; active = true }, retryEnabled = !active)
        }
    }

    @Composable
    private fun MarkdownFixture() {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp)) {
            com.zero.v2.app.ui.MessageBubble(com.zero.v2.core.common.ChatMessage(id = "markdown-fixture",
                role = com.zero.v2.core.common.MessageRole.ASSISTANT,
                content = "# 正文验收\n\n````markdown\n$MARKDOWN_CODE\n````\n\n代码后正文\n\n" +
                    "| 项 | 内容 | 最右列 |\n| --- | --- | --- |\n| 来源 | 已确认 | TABLE_END |\n\nMARKDOWN_END"))
        }
    }

    companion object {
        @Volatile var fontScaleForNextLaunch: Float? = null
        val MARKDOWN_CODE = "```kotlin\nval value = \"CODE_START_${"0123456789".repeat(12)}_CODE_END\"\n```"
    }

    @Composable
    private fun TraceFixture() {
        var expanded by remember { mutableStateOf(true) }
        val traces = remember {
            listOf(AgentTrace("visual", 1, nodeType = NodeType.LLM_REASONING,
                sourceModule = "fixture", summary = "核对文件来源，再整理回答。", tokensUsed = 120),
                AgentTrace("visual", 2, nodeType = NodeType.TOOL_CALL_START,
                    sourceModule = "fixture", summary = "只读查找工作文件", durationMs = 230),
                AgentTrace("visual", 3, nodeType = NodeType.SYSTEM_EVENT,
                    sourceModule = "fixture", summary = "资料已就绪，不需要重复获取。"))
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Spacer(Modifier.height(32.dp))
            // Reproduce inherited white text without opening a personal conversation.
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                AgentTraceTimeline(traces, expanded, { expanded = !expanded })
            }
        }
    }
}
