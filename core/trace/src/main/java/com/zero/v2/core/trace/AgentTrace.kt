package com.zero.v2.core.trace

/**
 * 单条 Agent 追踪记录 — 白盒可观测性的基本单元。
 *
 * 每次 LLM 交互、工具调用、记忆操作、异常捕获都产生一条记录。
 * 通过 traceId 串联同一次对话或自动化执行的所有步骤。
 * UI "Agent 思维链 timeline" 页面按 traceId 分组展示。
 */
data class AgentTrace(
    /** 追踪 ID（一次对话/自动化执行 traceId 不变） */
    val traceId: String,
    /** 递增序号 */
    val sequence: Int,
    /** 时间戳 (epoch millis) */
    val timestamp: Long = System.currentTimeMillis(),
    /** 节点类型 */
    val nodeType: NodeType,
    /** 来源模块 ID（如 "chatbot"、"web_search"、"system_event"） */
    val sourceModule: String,
    /** 一行摘要 */
    val summary: String,
    /** 详细信息（可折叠，如完整 LLM reasoning 或 ToolCall params） */
    val detail: String? = null,
    /** Token 消耗 */
    val tokensUsed: Long? = null,
    /** 耗时 (ms) */
    val durationMs: Long? = null,
    /** 父追踪 ID（嵌套调用，如 ToolCall 内部再调 LLM） */
    val parentTraceId: String? = null,
    /** 结构化工具调用 ID；历史事件和非工具事件为空。 */
    val callId: String? = null,
    /** 结构化函数名；历史事件和非工具事件为空。 */
    val functionName: String? = null
)

/**
 * Trace 写入的结构化输入。
 *
 * `summary` 仅用于人类展示，不承担机器协议；旧 record(...) 调用通过适配器继续可用。
 */
data class AgentTraceEvent(
    val traceId: String,
    val nodeType: NodeType,
    val sourceModule: String,
    val summary: String,
    val detail: String? = null,
    val tokensUsed: Long? = null,
    val durationMs: Long? = null,
    val parentTraceId: String? = null,
    val callId: String? = null,
    val functionName: String? = null
)

/**
 * 追踪节点类型。
 */
enum class NodeType {
    /** 用户输入 */
    USER_INPUT,
    /** LLM 推理/思考过程 */
    LLM_REASONING,
    /** LLM 输出 */
    LLM_OUTPUT,
    /** 工具调用开始 */
    TOOL_CALL_START,
    /** 工具调用结果 */
    TOOL_CALL_RESULT,
    /** Poke 消息收到 */
    POKE_RECEIVED,
    /** Poke 消息发送 */
    POKE_SENT,
    /** 记忆压缩 */
    COMPRESSION,
    /** 守护检查 */
    GUARD_CHECK,
    /** 异常/错误 */
    ERROR,
    /** 系统事件 */
    SYSTEM_EVENT,
    /** 配置变更 */
    CONFIG_CHANGE
}
