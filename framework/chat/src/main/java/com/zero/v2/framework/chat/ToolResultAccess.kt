package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MessageRole

/** Request projection only; the conversation remains the authoritative full result. */
internal object ToolResultAccess {
    const val NAME = "read_tool_result"
    private const val INLINE_LIMIT = 16000
    private const val PREVIEW_HEAD = 3000
    private const val PREVIEW_TAIL = 1000
    private const val PAGE_LIMIT = 6000

    val definition = FunctionDefinition(
        NAME,
        "只读获取本会话已执行工具的完整原文，无需重新执行。按nextOffset连续读取；可用query定位原文片段。offset/limit按Unicode字符计。",
        mapOf("type" to "object", "properties" to mapOf(
            "ref" to mapOf("type" to "string", "description" to "结果预览中的ref"),
            "offset" to mapOf("type" to "integer", "minimum" to 0),
            "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to PAGE_LIMIT),
            "query" to mapOf("type" to "string", "description" to "可选：从offset起查找完全匹配文本并返回该处页面"),
        ), "required" to listOf("ref")),
    )

    fun tools(catalog: List<FunctionDefinition>): List<FunctionDefinition> =
        catalog.filterNot { it.name == NAME } + definition

    fun reference(message: ChatMessage): String =
        GenerationProjection.digest(message.id + ":" + GenerationProjection.digest(message.content.orEmpty()))

    fun project(message: ChatMessage): ChatMessage {
        val text = message.content ?: return message
        if (message.role != MessageRole.TOOL || text.length <= INLINE_LIMIT) return message
        val count = text.codePointCount(0, text.length)
        val headEnd = text.offsetByCodePoints(0, minOf(PREVIEW_HEAD, count))
        val tailStart = text.offsetByCodePoints(text.length, -minOf(PREVIEW_TAIL, count))
        return message.copy(content = "[工具结果预览；非全文；totalChars=$count]\n" +
            "read_tool_result ref=${reference(message)} offset=0；可分页完整读取或query定位，勿为取原文重复执行。\n" +
            "--- head ---\n${text.substring(0, headEnd)}\n--- omitted ---\n" +
            "--- tail ---\n${text.substring(tailStart)}")
    }

    fun read(history: List<ChatMessage>, ref: String, offset: Int = 0, limit: Int = 4000, query: String = ""): String {
        require(offset >= 0 && limit in 1..PAGE_LIMIT) { "RESULT_RANGE_INVALID: limit=1..$PAGE_LIMIT, offset>=0" }
        val message = history.firstOrNull { it.role == MessageRole.TOOL && reference(it) == ref }
        requireNotNull(message) { "RESULT_NOT_FOUND: 原结果不在当前会话或已更改；不能读取其他会话或文件" }
        val text = message.content.orEmpty()
        val total = text.codePointCount(0, text.length)
        require(offset <= total) { "RESULT_RANGE_INVALID: totalChars=$total" }
        val from = text.offsetByCodePoints(0, offset)
        val start = if (query.isEmpty()) from else text.indexOf(query, from)
        if (start < 0) return "[工具结果原文 query未匹配；totalChars=$total；可省略query分页读取]"
        val actualOffset = text.codePointCount(0, start)
        val end = text.offsetByCodePoints(start, minOf(limit, total - actualOffset))
        val next = actualOffset + text.codePointCount(start, end)
        return "[工具结果原文 offset=$actualOffset nextOffset=$next totalChars=$total eof=${next == total}]\n" +
            text.substring(start, end)
    }
}
