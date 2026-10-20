package com.zero.v2.core.modulemanager

import com.zero.v2.core.bus.ChatBus
import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.bus.ToolTraceEvent
import com.zero.v2.core.common.*
import com.zero.v2.core.data.dao.AgentTraceEventDao
import com.zero.v2.core.trace.AgentTraceManager
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-6a：用真实 FunctionRegistryModule 的 catalog 输出建立稳定代表性基线。
 * 这里只测量，不改变生产注册或工具选择语义；数值记录在推进文档。
 */
class ToolSchemaBaselineTest {

    @Test
    fun representativeCatalog_schemaBaseline_isDeterministic() {
        val registry = testRegistry(FakeChatBus(), AgentTraceManager(NoOpTraceDao()))
        representativeDefinitions().forEach { definition ->
            registry.register(definition, "baseline") { FunctionResult.success("ok") }
        }

        val definitions = registry.getAllDefinitions()
        val serialized = serializeTools(definitions)
        val compactedSerialized = serializeTools(
            definitions.map { it.copy(parameters = ToolSchemaCompactor.compact(it.parameters)) },
        )
        val metrics = BaselineMetrics(
            toolCount = definitions.size,
            charCount = serialized.length,
            byteCount = serialized.toByteArray(Charsets.UTF_8).size,
            estimatedTokens = (serialized.length + 3) / 4,
        )

        assertEquals(4, metrics.toolCount)
        assertTrue(metrics.charCount > 0)
        assertEquals(metrics.charCount, serialized.length)
        assertTrue(metrics.byteCount >= metrics.charCount)
        assertEquals((metrics.charCount + 3) / 4, metrics.estimatedTokens)
        System.err.println("P2-6a tool schema baseline: $metrics")
        System.err.println(
            "P2-6c compacted schema: chars=${compactedSerialized.length}, " +
                "bytes=${compactedSerialized.toByteArray(Charsets.UTF_8).size}, " +
                "estimatedTokens=${(compactedSerialized.length + 3) / 4}, " +
                "savedChars=${serialized.length - compactedSerialized.length}",
        )
        assertTrue(compactedSerialized.length <= serialized.length)
    }

    private fun representativeDefinitions() = listOf(
        FunctionDefinition(
            "web_search",
            "搜索互联网并返回结果摘要。",
            mapOf("type" to "object", "properties" to mapOf("query" to mapOf("type" to "string")), "required" to listOf("query")),
        ),
        FunctionDefinition(
            "linux_exec",
            "在本地 Termux 执行命令。",
            mapOf("type" to "object", "properties" to mapOf("command" to mapOf("type" to "string")), "required" to listOf("command")),
        ),
        FunctionDefinition(
            "memory_recall",
            "搜索相关记忆。",
            mapOf("type" to "object", "properties" to mapOf("query" to mapOf("type" to "string"))),
        ),
        FunctionDefinition(
            "read_notifications",
            "读取系统通知。",
            mapOf("type" to "object", "properties" to emptyMap<String, Any?>()),
        ),
    )

    private fun serializeTools(definitions: List<FunctionDefinition>): String {
        return definitions.joinToString(prefix = "[", postfix = "]") { definition ->
            "{\"type\":\"function\",\"function\":{" +
                "\"name\":${jsonString(definition.name)}," +
                "\"description\":${jsonString(definition.description)}," +
                "\"parameters\":${jsonValue(definition.parameters)}" +
                "}}"
        }
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> jsonString(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "${jsonString(key.toString())}:${jsonValue(item)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
        else -> jsonString(value.toString())
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private data class BaselineMetrics(
        val toolCount: Int,
        val charCount: Int,
        val byteCount: Int,
        val estimatedTokens: Int,
    )

    private class NoOpTraceDao : AgentTraceEventDao {
        override suspend fun insert(event: com.zero.v2.core.data.entity.AgentTraceEventEntity) = Unit
        override suspend fun getByTraceId(traceId: String) = emptyList<com.zero.v2.core.data.entity.AgentTraceEventEntity>()
        override suspend fun getByCallId(callId: String) = emptyList<com.zero.v2.core.data.entity.AgentTraceEventEntity>()
        override suspend fun getRecentTraces(limit: Int) = emptyList<com.zero.v2.core.data.dao.RecentTraceRow>()
        override suspend fun deleteOlderThan(before: Long) = 0
        override suspend fun count() = 0L
    }

    private class FakeChatBus : ChatBus {
        override val onChatSent = MutableSharedFlow<ChatSentEvent>()
        override val onChatReceived = MutableSharedFlow<StreamChunk>()
        override val onChatFinished = MutableSharedFlow<ChatFinishedEvent>()
        override val onChatHistoryAdd = MutableSharedFlow<ChatMessage>()
        override val onPokeSend = MutableSharedFlow<PokeEvent>()
        override val onToolCallStart = MutableSharedFlow<ToolCallStartEvent>()
        override val onToolCallResult = MutableSharedFlow<ToolCallResultEvent>()
        override val onUserPriorityInterrupt = MutableSharedFlow<Unit>()
        override val onToolCallEvent = MutableSharedFlow<ToolTraceEvent>()
    }

    private class FakeSystemBus : SystemBus {
        override val onTick = MutableSharedFlow<Long>()
        override val onScheduleTriggered = MutableSharedFlow<ScheduleTriggeredEvent>()
        override val onNotificationPosted = MutableSharedFlow<NotificationEvent>()
        override val onAppForeground = MutableSharedFlow<String>()
        override val onNetworkChanged = MutableSharedFlow<Boolean>()
        override val onBatteryChanged = MutableSharedFlow<BatteryEvent>()
        override val onVoiceActive = MutableSharedFlow<Boolean>()
        override val onMusicDue = MutableSharedFlow<Unit>()
        override val onFilesChanged = MutableSharedFlow<List<String>>()
        override val onSecurityConfirmRequest = MutableSharedFlow<SecurityConfirmRequest>()
        override val onSecurityConfirmResponse = MutableSharedFlow<SecurityConfirmResponse>()
    }
}
