package com.zero.v2.core.common

import java.security.MessageDigest

/**
 * 回合 prompt 计划（迭代开发设计§2.1 / 落地计划 C1）。
 *
 * 把一次 LLM 请求拆为稳定前缀 + 可变上下文：
 *   - [stablePrefix] 字节稳定（固定 persona/协议/工具 manifest），连续轮相同输入产出相同字节，
 *     供 provider 前缀缓存命中；摘要/时间/情绪/证据等动态信息不入此段。
 *   - [prefixVersion] = SHA-256(provider协议版本 | modelId | stableSystem | toolManifest)，用于
 *     观测前缀一致性（不记原 prompt/Key）。
 *   - [history] 已保留历史（append-only，前缀缓存命中铁律）；[turnContext] 本轮短上下文
 *     （摘要水印/证据/动态风格，发请求时注入历史之后、当前内容之前）；[selectedTools] 本轮工具声明。
 */
data class PromptPlan(
    val stablePrefix: String,
    val prefixVersion: String,
    val history: List<ChatMessage>,
    val turnContext: String?,
    val selectedTools: List<FunctionDefinition>,
)

/**
 * 稳定前缀计算（C1 纯逻辑）——确定性强、可单测。
 *
 * 哈希输入严格遵循设计§4.1：provider协议版本 | modelId | stableSystemBytes | toolManifestBytes。
 * 摘要/时间/情绪/RAG/工具检索结果不进哈希；角色/profile/工具 manifest 变更必改哈希。
 * 工具 manifest 按 name 排序、字段顺序固定，重排不改变字节（确定性）。
 */
object PromptPrefix {
    /** 协议版本前缀（provider 请求序列化约定；codec 变更即作废所有前缀缓存）。M3 多模态落地时升 v2。 */
    const val PROVIDER_PROTOCOL_V1 = "openai_compat_v1"

    /** 工具 manifest 字节：按 name 排序，每条 name+description+parameters 确定性序列化。 */
    fun toolManifest(tools: List<FunctionDefinition>): String =
        tools.sortedBy { it.name }.joinToString("\n") { t ->
            "${t.name}\t${t.description}\t${deterministicSchema(t.parameters)}"
        }

    /** 稳定系统主体（无工具）：协议头 + persona。 */
    fun stableSystem(persona: String, protocol: String): String =
        buildString {
            appendLine(protocol)
            appendLine("---")
            append(persona)
        }

    /** Tool schemas are sent only through the provider tools field; they are not duplicated in system text. */
    @Suppress("UNUSED_PARAMETER")
    fun stablePrefix(persona: String, protocol: String, tools: List<FunctionDefinition>): String =
        stableSystem(persona, protocol)

    /**
     * prefixVersion = SHA-256(provider协议 | modelId | stableSystem | toolManifest)，hex。
     * 用 \u0001 分隔避免字段值跨边界歧义；输入为稳定段（不含动态上下文）。
     */
    fun prefixVersion(
        providerProtocol: String,
        modelId: String,
        stableSystem: String,
        toolManifest: String,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = "$providerProtocol\u0001$modelId\u0001$stableSystem\u0001$toolManifest"
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 参数 schema 确定性序列化：Map 按 key 排序、List 保序、原语直出（供 manifest 哈希稳定）。 */
    private fun deterministicSchema(value: Any?): String {
        fun Any?.render(): String = when (this) {
            is Map<*, *> -> entries.sortedBy { it.key.toString() }
                .joinToString(",", "{", "}") { (k, v) -> "$k:${v.render()}" }
            is List<*> -> joinToString(",", "[", "]") { it.render() }
            is String -> "\"$this\""
            null -> "null"
            else -> toString()
        }
        return value.render()
    }
}

/**
 * provider usage 的缓存命中观测（C1）——携带到 trace 聚合，不记原 prompt/Key。
 *
 * 缺 usage（[present]=false）时 [ratioLabel]="unknown"，不报 0%——落实 C1 验收
 * "缺 usage 显示 unknown 而非 0%"。DeepSeek 用 prompt_cache_hit/miss_tokens；
 * OpenAI 用 cache_read/creation_input_tokens——两者归一为 [hitTokens]/[missTokens]。
 */
data class CacheObservation(
    val present: Boolean,
    val hitTokens: Long,
    val missTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
) {
    /** 缓存命中率标签：缺 usage=unknown；input=0=unknown；否则 hit*100/input 百分比（钳 0-100）。 */
    val ratioLabel: String
        get() = when {
            !present -> "unknown"
            inputTokens <= 0 -> "unknown"
            else -> "${(hitTokens * 100 / inputTokens).coerceIn(0L, 100L)}%"
        }

    companion object {
        /** usage 缺失（provider 未返回 usage）——单列 unknown，不当 0%。 */
        val ABSENT = CacheObservation(present = false, 0, 0, 0, 0, 0)

        /**
         * 从 usage 字段归一构造。DeepSeek：prompt_cache_hit_tokens / prompt_cache_miss_tokens；
         * OpenAI：prompt_tokens_details.cached_tokens / cache_read_input_tokens。
         * @param present provider是否返回了 usage（false→[ABSENT]）
         */
        fun fromUsage(
            present: Boolean,
            inputTokens: Long,
            hitTokens: Long,
            missTokens: Long,
            outputTokens: Long,
            totalTokens: Long,
        ): CacheObservation =
            if (!present) CacheObservation(false, 0, 0, inputTokens, outputTokens, totalTokens)
            else CacheObservation(true, hitTokens, missTokens, inputTokens, outputTokens, totalTokens)
    }
}
