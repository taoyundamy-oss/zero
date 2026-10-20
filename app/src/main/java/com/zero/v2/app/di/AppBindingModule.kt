package com.zero.v2.app.di

import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ITimedModule
import com.zero.v2.framework.chat.ChatBotModule
import com.zero.v2.framework.chat.CharacterStateModule
import com.zero.v2.framework.chat.ContextBuilderModule
import com.zero.v2.framework.chat.MessageFilterModule
import com.zero.v2.framework.memory.GraphExtractionJob
import com.zero.v2.framework.memory.MemoryModule
import com.zero.v2.framework.memory.MemoryReflectionJob
import com.zero.v2.core.modulemanager.FunctionRegistryModule
import com.zero.v2.core.modulemanager.DefaultToolContextSelector
import com.zero.v2.core.moduleapi.ToolContextSelector
import com.zero.v2.core.moduleapi.ModuleRuntimeStateReader
import com.zero.v2.core.moduleapi.FunctionCatalog
import com.zero.v2.core.moduleapi.ToolDispatcher
import com.zero.v2.core.moduleapi.ToolRegistrar
import com.zero.v2.core.moduleapi.SystemStateRepository
import com.zero.v2.app.system.SystemStateRepositoryImpl
import com.zero.v2.app.system.SystemStatusModule
import com.zero.v2.app.retrieval.EvidenceProviderImpl
import com.zero.v2.services.retrieval.EvidenceProvider
import com.zero.v2.services.security.CredentialStore
import com.zero.v2.services.security.SecureKeyStore
import com.zero.v2.services.agent.AgentJob
import com.zero.v2.services.agent.AgentRuntime
import com.zero.v2.services.agent.Scheduler
import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.services.embedding.RemoteEmbeddingProvider
import com.zero.v2.services.llm.OpenAiChatModule
import com.zero.v2.services.module.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/**
 * 将所有 IZeroModule 实现注册到 Hilt @IntoSet。
 * ModuleLifecycleManager 通过 Set<IZeroModule> 自动收集。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingModule {

    // Core
    // N2.6b：DeepSeek/Qwen Chat 收敛入 LLM 统一接入（legacy 两模块已删，按 Profile 注册）
    @Binds @IntoSet abstract fun bindOpenAiChat(m: OpenAiChatModule): IZeroModule
    @Binds @IntoSet abstract fun bindChatBot(m: ChatBotModule): IZeroModule
    /** P3：动态角色状态模块（reducer 接线 + 风格指令 + 角色页状态读/写） */
    @Binds @IntoSet abstract fun bindCharacterState(m: CharacterStateModule): IZeroModule
    @Binds @IntoSet abstract fun bindContextBuilder(m: ContextBuilderModule): IZeroModule
    @Binds @IntoSet abstract fun bindMessageFilter(m: MessageFilterModule): IZeroModule
    @Binds @IntoSet abstract fun bindFunctionRegistry(m: FunctionRegistryModule): IZeroModule
    @Binds abstract fun bindFunctionCatalog(m: FunctionRegistryModule): FunctionCatalog

    @Binds abstract fun bindToolContextSelector(m: DefaultToolContextSelector): ToolContextSelector
    @Binds abstract fun bindModuleRuntimeStateReader(m: com.zero.v2.core.modulemanager.ModuleLifecycleManager): ModuleRuntimeStateReader
    @Binds abstract fun bindToolExecutionGuard(m: com.zero.v2.core.modulemanager.ModuleLifecycleManager): com.zero.v2.core.moduleapi.ToolExecutionGuard
    @Binds abstract fun bindToolDispatcher(m: FunctionRegistryModule): ToolDispatcher
    @Binds abstract fun bindToolRegistrar(m: FunctionRegistryModule): ToolRegistrar
    @Binds abstract fun bindToolRuntime(m: FunctionRegistryModule): com.zero.v2.core.moduleapi.ToolRuntime
    @Binds abstract fun bindCredentialStore(m: SecureKeyStore): CredentialStore
    @Binds abstract fun bindSystemStateRepository(impl: SystemStateRepositoryImpl): SystemStateRepository
    /** R3：证据提供方（impl 注入 MemoryModule+KnowledgeModule 跨域融合） */
    @Binds abstract fun bindEvidenceProvider(impl: EvidenceProviderImpl): EvidenceProvider
    /** S1：系统状态查询模块（zero_status FC，只读 AUTO） */
    @Binds @IntoSet abstract fun bindSystemStatus(m: SystemStatusModule): IZeroModule
    @Binds @IntoSet abstract fun bindAgentRuntime(m: AgentRuntime): IZeroModule
    @Binds @IntoSet abstract fun bindScheduler(m: Scheduler): IZeroModule
    @Binds @IntoSet abstract fun bindSpeechTts(m: com.zero.v2.services.realtime.SpeechSynthesisModule): IZeroModule
    // v6.20 规范化补账（设计稿 §3.1/§3.3）：ASR/VAD 升正式 IZeroModule
    @Binds @IntoSet abstract fun bindSpeechAsr(m: com.zero.v2.services.realtime.SpeechRecognitionModule): IZeroModule
    @Binds @IntoSet abstract fun bindSpeechVad(m: com.zero.v2.services.realtime.VoiceActivityModule): IZeroModule
    @Binds @IntoSet abstract fun bindScreenCapture(m: com.zero.v2.services.realtime.ScreenCaptureModule): IZeroModule
    // N3：WorkFiles + OfficeFiles 合并 → FilesModule（FC 名/权限原样）
    @Binds @IntoSet abstract fun bindFiles(m: com.zero.v2.app.data.FilesModule): IZeroModule

    /** 音乐轨 MU1.2：本地音乐库模块（app 层，依赖 WorkDirRepository） */
    @Binds @IntoSet abstract fun bindMusic(m: com.zero.v2.app.music.MusicModule): IZeroModule

    /** 音乐轨 MU4.2b：AgentJob 双绑定（SystemEventModule 先例 :91-92——Scheduler 轮询驱动） */
    @Binds @IntoSet abstract fun bindMusicJob(m: com.zero.v2.app.music.MusicModule): AgentJob

    /** 知识库轨 KB1.3：knowledge/ 子树索引模块（app 层，依赖 WorkDirRepository）+ AgentJob 周期索引双绑定 */
    @Binds @IntoSet abstract fun bindKnowledge(m: com.zero.v2.app.knowledge.KnowledgeModule): IZeroModule
    @Binds @IntoSet abstract fun bindSkills(m: com.zero.v2.app.skills.SkillModule): IZeroModule
    @Binds @IntoSet abstract fun bindKnowledgeJob(m: com.zero.v2.app.knowledge.KnowledgeModule): AgentJob

    /** 知识库轨 KB3.2：Assistant 模块（配置承载 + HotwordService 生命周期编排） */
    @Binds @IntoSet abstract fun bindAssistant(m: com.zero.v2.app.assistant.AssistantModule): IZeroModule

    /** 模块化轨 M1.3：Shizuku 操作层 + Termux 工作站基建进模块集
     *（M1.1/M1.2 代码自此激活——操作 FC×9 注册、Shizuku 绑定，L3 实测项自此可测） */
    @Binds @IntoSet abstract fun bindShizuku(m: com.zero.v2.services.shizuku.ShizukuBridge): IZeroModule
    @Binds @IntoSet abstract fun bindTermux(m: com.zero.v2.services.termux.TermuxBridge): IZeroModule
    // Step 8.1（D57）：网络资源获取
    @Binds @IntoSet abstract fun bindWebFetch(m: com.zero.v2.app.data.WebFetchModule): IZeroModule
    // Step 8.3：MCP 客户端（吸收 McpTestModule）
    @Binds @IntoSet abstract fun bindMcpClient(m: com.zero.v2.services.mcp.McpClientModule): IZeroModule
    // M3.2（D67）：代码开发元工具（develop_module 全 AUTO）
    @Binds @IntoSet abstract fun bindCodeDev(m: com.zero.v2.services.module.CodeDevModule): IZeroModule
    // Step 9.2 (D58): 位置服务
    @Binds @IntoSet abstract fun bindLocation(m: com.zero.v2.services.module.LocationModule): IZeroModule
    // Step 9.3 (D58): 日历
    @Binds @IntoSet abstract fun bindCalendar(m: com.zero.v2.services.module.CalendarModule): IZeroModule
    // Step 9.4 (D58): 系统闹钟
    @Binds @IntoSet abstract fun bindAlarm(m: com.zero.v2.services.module.AlarmModule): IZeroModule
    // Step 9.5 (D58): Agent 自主思考（A2a D73：nextRunTime=MAX 禁 tick，仅事件驱动）
    @Binds @IntoSet abstract fun bindAutonomousThinking(m: com.zero.v2.services.agent.AutonomousThinkingJob): AgentJob
    // A2a：事件触发枢纽（通知白名单→门控链→直调自主思考）
    @Binds @IntoSet abstract fun bindTriggerHub(m: com.zero.v2.services.agent.TriggerHub): IZeroModule

    /** AgentJob 多绑定声明 — 允许空集（Step 3.4 起各模块 @Binds @IntoSet 贡献） */
    @Multibinds
    abstract fun agentJobs(): Set<@JvmSuppressWildcards AgentJob>

    // Embedding（v4.6-pre，D31 检索主径）
    @Binds
    abstract fun bindEmbeddingProvider(impl: com.zero.v2.services.embedding.SelectedEmbeddingProvider): EmbeddingProvider

    @Binds @IntoSet abstract fun bindEmbeddingBackend(m: com.zero.v2.services.embedding.EmbeddingBackendModule): IZeroModule

    // v6.3: D46 依赖反转——级联语音（services）→ ChatBot（framework.chat）桥接收敛于 app 层
    @Binds
    abstract fun bindChatEngineBridge(impl: ChatEngineBridgeImpl): com.zero.v2.services.realtime.ChatEngineBridge

    // S4.2 (D125): TTS WAV 落盘缝——services 侧合成，app 侧 SAF 工作目录写入
    @Binds
    abstract fun bindTtsWavSink(impl: TtsWavSinkImpl): com.zero.v2.services.realtime.TtsWavSink

    // Memory
    @Binds @IntoSet abstract fun bindMemory(m: MemoryModule): IZeroModule
    @Binds @IntoSet abstract fun bindMemoryVectorRetry(m: MemoryModule): AgentJob
    @Binds @IntoSet abstract fun bindMemoryDerivation(m: com.zero.v2.framework.memory.MemoryDerivationJob): AgentJob
    @Binds @IntoSet abstract fun bindReflectionJob(m: MemoryReflectionJob): AgentJob
    @Binds @IntoSet abstract fun bindGraphExtractionJob(m: GraphExtractionJob): AgentJob

    // Instrument
    @Binds @IntoSet abstract fun bindWebSearch(m: WebSearchModule): IZeroModule
    @Binds @IntoSet abstract fun bindVision(m: VisionModule): IZeroModule

    // System
    @Binds @IntoSet abstract fun bindSystemEvent(m: SystemEventModule): IZeroModule
    @Binds @IntoSet abstract fun bindSystemEventJob(m: SystemEventModule): AgentJob

    // Notification + UsageStats
    @Binds @IntoSet abstract fun bindNotification(m: NotificationModule): IZeroModule
    @Binds @IntoSet abstract fun bindUsageStats(m: UsageStatsModule): IZeroModule
    @Binds @IntoSet abstract fun bindUsageStatsTimed(m: UsageStatsModule): ITimedModule

    // Automation
    @Binds @IntoSet abstract fun bindAutomation(m: AutomationModule): IZeroModule
    @Binds @IntoSet abstract fun bindAutomationJob(m: AutomationModule): AgentJob
}
