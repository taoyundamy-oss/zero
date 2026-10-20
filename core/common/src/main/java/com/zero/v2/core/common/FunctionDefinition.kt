package com.zero.v2.core.common

/**
 * 函数定义 — OpenAI Function Calling 格式。
 *
 * 对标 Alife 的 XmlHandler + XmlFunction 属性。
 * 每个模块通过 FunctionRegistry.register() 注册其 FC 函数。
 *
 * v2.2 新增：
 *   - securityLevel: SAFE/SENSITIVE 安全分级
 *   - dependsOn: 显式依赖声明（用于并发执行排序）
 */
data class FunctionDefinition(
    /** 函数名称（如 "web_search"） */
    val name: String,
    /** 函数描述（供 LLM 决定何时调用） */
    val description: String,
    /** JSON Schema 格式的参数定义 */
    val parameters: Map<String, Any?> = emptyMap(),
    /**
     * v2.2: 安全级别。
     * SAFE — 只读操作，无需确认
     * SENSITIVE — 写入/推送操作，弹窗确认
     */
    val securityLevel: FunctionSecurity = FunctionSecurity.SAFE,
    /**
     * v2.2: 显式依赖声明。
     * 当 LLM 返回多个 tool_calls 时，声明此函数依赖哪些其他函数先完成。
     * 如 llm_synthesize 依赖 web_search 的结果。
     */
    val dependsOn: List<String> = emptyList()
)

enum class FunctionSecurity {
    SAFE,
    SENSITIVE
}
