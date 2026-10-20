package com.zero.v2.framework.chat

import com.zero.v2.core.bus.ChatBus
import com.zero.v2.core.common.ChatFinishedEvent
import com.zero.v2.core.common.AffinityBand
import com.zero.v2.core.common.CharacterStateEventKey
import com.zero.v2.core.common.CharacterStateReducer
import com.zero.v2.core.common.CharacterStyleDirective
import com.zero.v2.core.common.Mood
import com.zero.v2.core.data.dao.CharacterDao
import com.zero.v2.core.data.dao.CharacterStateDao
import com.zero.v2.core.data.entity.CharacterRelationState
import com.zero.v2.core.data.entity.CharacterStateEvent
import com.zero.v2.core.data.entity.ConversationMoodState
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleDescriptor
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态角色状态模块（P3 / 设计§6）。
 *
 * 三项职责（P2 接线 + P3 风格注入 + UI 读路径）：
 * 1. **reducer 接线（P2 续）**：订阅 [ChatBus.onChatFinished] → 读活跃角色 + 当前会话状态 →
 *    [CharacterStateReducer.reduce] → 事务写 CharacterRelationState/ConversationMoodState +
 *    幂等 CharacterStateEvent。回合后异步、不占 MessagePipeline 100ms filter 路径；失败仅留痕，
 *    不阻断对话（固定人设继续回复）。
 * 2. **风格指令读路径（P3）**：[currentStyleDirective] 供 ChatBot.sendMessage 读当前状态 →
 *    [CharacterStyleDirective.compose] → 注入 turnContext（不入 stablePrefix，缓存稳定）。
 *    无状态记录 → null → 固定 systemPrompt 继续生效。
 * 3. **UI 读/写路径（P3）**：[snapshot] 供角色页查看当前亲密度 + 最近变化依据；
 *    [dynamicStyleEnabled] 开关（角色页"关闭动态表达"，关闭不删除存量）；
 *    [resetRelationForCharacter]/[resetMoodForCharacter] 分别重置（设计§6"分别重置"）。
 *
 * 不改变 SAFE/CONFIRM/FORBIDDEN/工具权限/事实来源/危机响应（D1）——仅影响表达风格。
 */
@Singleton
class CharacterStateModule @Inject constructor(
    private val characterStateDao: CharacterStateDao,
    private val characterDao: CharacterDao,
    private val chatBus: ChatBus,
    private val moduleConfigStore: ModuleConfigStore,
    private val traceManager: AgentTraceManager,
) : ModuleDescriptor() {

    /** P2 已交付的纯规则引擎（默认 6h 冷却 / 30min TTL——P2 单测覆盖值）。 */
    private val reducer = CharacterStateReducer()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeMutex = Mutex()

    override val metadata = ModuleMetadata(
        id = "character_state",
        displayName = "动态角色状态",
        description = "亲密度/情绪状态投影与风格指令：回合后 reducer 更新状态、回合前注入风格指令、角色页查看/重置/开关",
        category = ModuleCategory.CORE,
        launchOrder = -60,  // ChatBot(-200) 之后、普通模块之前；订阅在 sendMessage 发生前就绪
    )

    override val config = CharacterStateConfig()

    override val requiredPermissions: List<String> = emptyList()

    private val _dynamicStyleEnabled = MutableStateFlow(true)
    /** 动态表达开关（角色页可关；关闭不删除存量状态，固定人设继续回复）。 */
    val dynamicStyleEnabled: StateFlow<Boolean> = _dynamicStyleEnabled.asStateFlow()

    override suspend fun onAwake() {
        // ModuleLifecycleManager 已在 onAwake 前加载持久化 config → 同步开关到 StateFlow
        _dynamicStyleEnabled.value = (config as CharacterStateConfig).dynamicStyleEnabled
    }

    override suspend fun onStart() {
        // 订阅对话完成 → 异步 reduce + 写入（失败仅留痕，不阻断对话）
        scope.launch {
            chatBus.onChatFinished.collect { event ->
                try { reduceAndPersist(event) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    traceManager.record("char_state", NodeType.ERROR, metadata.id,
                        "reduce_failed: ${e.javaClass.simpleName} ${e.message?.take(60)}")
                }
            }
        }
    }

    override suspend fun onDestroy() {
        scope.cancel()
    }

    // ═══════════════════════════════════════════════════════
    // 读路径：风格指令（ChatBot.sendMessage 调用）
    // ═══════════════════════════════════════════════════════

    /**
     * 当前风格指令（≤120 token）——供 ChatBot 注入 turnContext。
     *
     * 读活跃角色 + 会话情绪 → [CharacterStyleDirective.compose]。无状态记录/关闭 → null
     * （固定 systemPrompt 继续生效）。异常隔离返回 null（失败以固定人设继续回复，设计§6）。
     */
    suspend fun currentStyleDirective(conversationId: String): String? {
        if (conversationId.isBlank()) return null
        val enabled = _dynamicStyleEnabled.value
        if (!enabled) return null
        return try {
            val activeChar = characterDao.getActive() ?: return null
            val relation = characterStateDao.getRelationState(activeChar.id)
            val mood = characterStateDao.getMoodState(conversationId)
            val aff = relation?.let { AffinityBand.fromName(it.affinityBand) } ?: AffinityBand.DEFAULT
            val m = mood?.takeIf { it.expiresAt > System.currentTimeMillis() }
                ?.let { Mood.fromName(it.mood) } ?: Mood.DEFAULT
            CharacterStyleDirective.compose(aff, m, enabled)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            traceManager.record("char_state", NodeType.ERROR, metadata.id,
                "style_directive_failed: ${e.message?.take(50)}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════
    // 写路径：reducer 接线（P2 续——turn→reduce→DAO）
    // ═══════════════════════════════════════════════════════

    private suspend fun reduceAndPersist(event: ChatFinishedEvent) = writeMutex.withLock { reduceLocked(event) }

    private suspend fun reduceLocked(event: ChatFinishedEvent) {
        val conversationId = event.conversationId
        if (conversationId.isBlank()) return
        val userText = event.userMessage.content ?: ""
        val assistantText = event.assistantMessage.content ?: ""
        if (userText.isBlank()) return  // 无用户文本证据 → 不评估
        val activeChar = characterDao.getActive() ?: return  // 无活跃角色 → 不写

        val now = System.currentTimeMillis()
        val relation = characterStateDao.getRelationState(activeChar.id)
        val moodState = characterStateDao.getMoodState(conversationId)
        val currentAffinity = relation?.let { AffinityBand.fromName(it.affinityBand) } ?: AffinityBand.DEFAULT
        val currentMood = moodState?.let { Mood.fromName(it.mood) } ?: Mood.DEFAULT

        val out = reducer.reduce(CharacterStateReducer.Input(
            userText = userText,
            assistantText = assistantText,
            currentAffinity = currentAffinity,
            currentMood = currentMood,
            moodExpiresAt = moodState?.expiresAt ?: 0L,
            lastAffinityChangeAt = relation?.updatedAt ?: 0L,
            now = now,
        ))

        if (out == CharacterStateReducer.Output.NO_CHANGE) return  // 无关消息/无变化 → 不写

        run {
            // 亲密度推进（仅正向 +1，封顶 DEVOTED，冷却内不变，负面不降——reducer 约束）
            // 本地 val 捕获：out.newAffinity 跨模块 public 属性不可 smart cast
            val newAffinity = out.newAffinity
            if (newAffinity != null && newAffinity != currentAffinity) {
                val newRelation = CharacterRelationState(
                    characterId = activeChar.id,
                    affinityBand = newAffinity.name,
                    interactionCount = (relation?.interactionCount ?: 0) + 1,
                    updatedAt = now,
                    revision = (relation?.revision ?: 0) + 1,
                )
                val reason = out.reasonAffinity ?: "亲密度推进"
                characterStateDao.applyRelationChange(newRelation, CharacterStateEvent(
                    id = UUID.randomUUID().toString(),
                    characterId = activeChar.id,
                    conversationId = conversationId,
                    sourceMessageId = event.userMessage.id,
                    changeType = CharacterStateEvent.CHANGE_TYPE_AFFINITY,
                    reason = reason,
                    idempotencyKey = CharacterStateEventKey.compute(
                        CharacterStateEvent.CHANGE_TYPE_AFFINITY, event.userMessage.id, reason),
                ))
                traceManager.record("char_state", NodeType.SYSTEM_EVENT, metadata.id,
                    "affinity_advanced: char=${activeChar.id.take(8)} $currentAffinity→$newAffinity reason=$reason")
            }
            // 情绪变化（即时事件驱动，有 TTL；含衰减回 neutral）
            val newMood = out.newMood
            if (newMood != null && (newMood != currentMood || (moodState?.expiresAt ?: 0L) <= now)) {
                val newMoodState = ConversationMoodState(
                    conversationId = conversationId,
                    mood = newMood.name,
                    intensity = out.newMoodIntensity,
                    expiresAt = out.newMoodExpiresAt,
                    updatedAt = now,
                    revision = (moodState?.revision ?: 0) + 1,
                )
                val reason = out.reasonMood ?: "情绪变化"
                characterStateDao.applyMoodChange(newMoodState, CharacterStateEvent(
                    id = UUID.randomUUID().toString(),
                    characterId = activeChar.id,
                    conversationId = conversationId,
                    sourceMessageId = event.userMessage.id,
                    changeType = CharacterStateEvent.CHANGE_TYPE_MOOD,
                    reason = reason,
                    idempotencyKey = CharacterStateEventKey.compute(
                        CharacterStateEvent.CHANGE_TYPE_MOOD, event.userMessage.id, reason),
                ))
                traceManager.record("char_state", NodeType.SYSTEM_EVENT, metadata.id,
                    "mood_changed: conv=${conversationId.take(8)} $currentMood→$newMood reason=$reason")
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // UI 读/写路径：查看 / 重置 / 开关
    // ═══════════════════════════════════════════════════════

    /** 角色页快照：当前亲密度 + 最近变化依据事件。 */
    suspend fun snapshot(characterId: String): CharacterStateSnapshot? = try {
        val relation = characterStateDao.getRelationState(characterId)
        val mood = characterStateDao.latestMoodForCharacter(characterId)
        val events = characterStateDao.recentEventsForCharacter(characterId, limit = 5)
        CharacterStateSnapshot(
            affinity = relation?.let { AffinityBand.fromName(it.affinityBand) } ?: AffinityBand.DEFAULT,
            interactionCount = relation?.interactionCount ?: 0,
            revision = relation?.revision ?: 0,
            recentEvents = events.map {
                StateEventItem(changeType = it.changeType, reason = it.reason, createdAt = it.createdAt)
            },
            lastAffinityChangeAt = relation?.updatedAt ?: 0L,
            latestMood = mood?.let { Mood.fromName(it.mood) } ?: Mood.DEFAULT,
            moodExpiresAt = mood?.expiresAt ?: 0L,
        )
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    /** 重置亲密度（按角色）——删 CharacterRelationState；FK CASCADE 不触（state 表独立）。 */
    suspend fun resetRelationForCharacter(characterId: String) {
        writeMutex.withLock {
            characterStateDao.deleteRelationState(characterId)
            traceManager.record("char_state", NodeType.SYSTEM_EVENT, metadata.id,
                "affinity_reset: char=${characterId.take(8)}")
        }
    }

    /** 重置情绪（按角色——清该角色所有会话 mood 状态；情绪短时/按会话，整角色清空=新会话体感）。 */
    suspend fun resetMoodForCharacter(characterId: String) {
        writeMutex.withLock {
            characterStateDao.deleteMoodStatesForCharacter(characterId)
            traceManager.record("char_state", NodeType.SYSTEM_EVENT, metadata.id,
                "mood_reset: char=${characterId.take(8)}")
        }
    }

    /** 开关动态表达（角色页"关闭动态表达"；关闭不删除存量状态）。持久化到 module_configs。 */
    suspend fun setDynamicStyleEnabled(enabled: Boolean) = writeMutex.withLock {
        currentCoroutineContext().ensureActive()
        val candidate = CharacterStateConfig().apply {
            fromJson(config.toJson())
            dynamicStyleEnabled = enabled
        }
        // Once local persistence begins, complete commit and publication together. A late caller
        // cancellation is still propagated by its owner, without leaving DB and runtime divergent.
        withContext(NonCancellable) {
            moduleConfigStore.saveConfirmed(metadata.id, candidate.toJson())
            (config as CharacterStateConfig).dynamicStyleEnabled = enabled
            _dynamicStyleEnabled.value = enabled
        }
        traceManager.record("char_state", NodeType.SYSTEM_EVENT, metadata.id,
            "dynamic_style_enabled=$enabled")
        Unit
    }
}

/** 角色页状态快照（UI 用）。 */
data class CharacterStateSnapshot(
    val affinity: AffinityBand,
    val interactionCount: Int,
    val revision: Int,
    val recentEvents: List<StateEventItem>,
    val lastAffinityChangeAt: Long = 0L,
    val latestMood: Mood = Mood.DEFAULT,
    val moodExpiresAt: Long = 0L,
)

/** 最近变化依据（UI 展示用，idempotency 事件投影）。 */
data class StateEventItem(
    val changeType: String,
    val reason: String,
    val createdAt: Long,
)

/**
 * 动态角色状态配置（P3）。
 *
 * 持久化到 module_configs 表（ModuleLifecycleManager onAwake 前加载）。
 * 旧配置无 dynamicStyleEnabled 字段 → 默认 true（开启动态表达）。
 */
class CharacterStateConfig : ModuleConfigBase() {
    @ConfigField(
        label = "动态角色表达", fieldType = ConfigFieldType.TOGGLE,
        description = "开启后按角色亲密度/会话情绪生成短风格指令注入对话（仅影响语气，不改事实/权限/安全）。" +
                "关闭则停用动态表达（存量状态不删除，固定人设继续回复）。默认开启"
    )
    var dynamicStyleEnabled: Boolean = true

    override fun toJson(): String = """{"dynamicStyleEnabled":$dynamicStyleEnabled}"""

    override fun fromJson(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            dynamicStyleEnabled = obj.optBoolean("dynamicStyleEnabled", true)
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = CharacterStateConfig()
}
