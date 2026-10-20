package com.zero.v2.app.di

import com.zero.v2.app.data.ConversationRepository
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.MessageSource
import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.moduleapi.ToolContextRequest
import com.zero.v2.core.moduleapi.ToolContextSelector
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.framework.chat.ChatBotModule
import com.zero.v2.services.realtime.ChatEngineBridge
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ChatEngineBridge app 层实现 — v5.1 安全增强。
 *
 * 桥接 VoiceProvider（services）→ ChatBotModule（framework.chat），打破 services ↔ framework.chat 循环依赖。
 * v5.1: StringBuilder 聚合 + 2 分钟超时 + 错误提示。
 * v5.4: 注入 FunctionRegistryModule 传全量 FC 定义（语音轮工具可见）。
 * v5.5: collect 中回写 applyStreamChunk（FC 循环闭环）+ onStatus 工具/缓存反馈。
 * v6.19 (Step 6.12): **分句流式管线**——token 流按标点断句，onSentence 逐句下发，
 * 首句即播（首音延迟 = 首句生成而非全文生成）；小数点消歧（'.' 后随数字不切）；
 * 流末尾冲刷余量。
 */
@Singleton
class ChatEngineBridgeImpl @Inject constructor(
    private val chatBot: ChatBotModule,
    private val toolContextSelector: ToolContextSelector,
    private val conversationRepo: ConversationRepository,
    private val traceManager: AgentTraceManager
) : ChatEngineBridge {
    private data class CompletedTurn(
        val triggerId: String?, val systemSource: String?, val history: com.zero.v2.framework.chat.ChatHistory,
    )
    private val completedTurns = java.util.ArrayDeque<CompletedTurn>()

    override suspend fun chatAndCollect(
        userText: String,
        source: String,
        onStatus: ((String) -> Unit)?,
        onSentence: ((String) -> Unit)?,
        triggerId: String?,  // D1-fix（A1b 接线）：贯通至 TurnContext（autonomous_notify D79 校验）
        systemSource: String?  // S2R.3：接口默认参数互锁——override 必须同步参数表（R11）；本步仅机械传参
    ): String {
        val msgSource = when (source) {
            "VOICE" -> MessageSource.VOICE
            "AUTOMATION" -> MessageSource.AUTOMATION
            "POKE" -> MessageSource.POKE
            "AUTONOMOUS" -> MessageSource.AUTONOMOUS  // A1a：自主思考回合
            else -> MessageSource.CHAT_WINDOW
        }
        val reply = StringBuilder()
        var usage = TokenUsage()
        val reportedToolIds = mutableSetOf<String>()
        var turnSnapshot: com.zero.v2.framework.chat.ChatHistory? = null

        // v6.19 分句缓冲：token 增量 → 句级下发
        val sentenceBuf = StringBuilder()
        var pendingDot = false  // '.' 待下一字符消歧（数字 = 小数不切句）
        fun flushSentence() {
            val s = sentenceBuf.toString().trim()
            sentenceBuf.clear()
            if (s.isNotEmpty()) onSentence?.invoke(s)
        }

        try {
            withTimeout(120_000L) {
                chatBot.sendMessage(
                    userText,
                    functions = toolContextSelector.select(chatBot.toolSelectionRequest(userText)),
                    source = msgSource,
                    triggerId = triggerId,
                    systemSource = systemSource,
                )
                    .collect { chunk ->
                        chunk.contentDelta?.let { delta ->
                            reply.append(delta)
                            if (onSentence != null) {
                                for (ch in delta) {
                                    if (pendingDot) {
                                        pendingDot = false
                                        if (!ch.isDigit() && sentenceBuf.length >= 2) flushSentence()
                                    }
                                    sentenceBuf.append(ch)
                                    when (ch) {
                                        '。', '！', '？', '；', '\n', '!', '?', ';' ->
                                            if (sentenceBuf.length >= 2) flushSentence()
                                        '.' -> pendingDot = true
                                    }
                                }
                            }
                        }
                        // FC 循环闭环：toolCalls/content 必须回写 ChatHistory，否则循环判定无调用直接退出
                        chatBot.applyStreamChunk(chunk)
                        turnSnapshot = chatBot.chatHistory.value
                        chunk.tokenUsage?.let {
                            usage = usage + it
                        }
                        chunk.toolCallDelta?.let { delta ->
                            if (delta.id != null && delta.name != null && delta.id !in reportedToolIds) {
                                reportedToolIds.add(delta.id!!)
                                onStatus?.invoke("🔧 调用工具: ${delta.name}，请稍候…")
                            }
                        }
                    }
            }
        } catch (e: TimeoutCancellationException) {
            return "⚠️ 语音对话超时（2 分钟），请重试"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return "⚠️ 语音对话失败: ${e.message?.take(50) ?: "未知错误"}"
        } finally {
            turnSnapshot?.let { snapshot ->
                synchronized(completedTurns) {
                    completedTurns.addLast(CompletedTurn(triggerId, systemSource, snapshot))
                    while (completedTurns.size > 8) completedTurns.removeFirst()
                }
            }
        }
        // 流末尾冲刷余量（含未消歧的 pendingDot）
        flushSentence()
        // P2: 缓存命中可视化（Live 与 ChatScreen 同口径，D43）
        if (usage.inputTokens > 0) {
            onStatus?.invoke("⚡ ${usage.totalTokens}t · ${usage.cacheSummary}")
        }
        return reply.toString()
    }

    // ═══ A1a D79：查询方法实现（读 ChatBotModule 状态，无副作用）═══

    override fun isConversationIdle(): Boolean =
        chatBot.chatState.value != com.zero.v2.core.common.ChatState.GENERATING

    override fun activeTurn(): com.zero.v2.core.common.TurnContext? = chatBot.activeTurn.get()

    // ═══ S2R.4：回合留痕（桥缝 app 实装——TtsWavSink 先例；persist_turn 关键路径）═══

    override suspend fun persistTurn(triggerId: String?, systemSource: String?): Int {
        return try {
            val history = synchronized(completedTurns) {
                val saved = completedTurns.firstOrNull { it.triggerId == triggerId && it.systemSource == systemSource }
                if (saved != null) completedTurns.remove(saved)
                saved?.history
            } ?: chatBot.chatHistory.value
            val messages = history.messages
            // 本回合切片：本 triggerId 的系统消息（userMsg）起，至下一条 USER 前——精确圈定，
            // 不含后续回合/用户直输消息（防与 VM persistConversationTurn 双写）
            val startIdx = messages.indexOfLast {
                it.role == MessageRole.USER && it.triggerId == triggerId
            }
            if (startIdx < 0) return 0  // 无本回合系统消息（等闲跳过等）——零落库
            conversationRepo.persistTurn(history.id, messages, messages[startIdx].id)
        } catch (e: Exception) {
            // E28 铁律：静默失败必留痕（FK 失配/会话未建等在此显形）
            traceManager.record("persist_turn", NodeType.ERROR, "chat_engine_bridge",
                "persist_turn_failed: ${e.javaClass.simpleName} ${e.message?.take(60)} " +
                        "triggerId=${triggerId?.take(8)}")
            -1
        }
    }
}
