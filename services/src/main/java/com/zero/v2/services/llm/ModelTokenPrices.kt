package com.zero.v2.services.llm

import com.zero.v2.core.common.TokenUsage
import java.math.BigDecimal
import org.json.JSONObject

/** User supplied USD per million tokens. Separate from inference parameters and cache identity. */
data class ModelTokenPrices(
    val input: String = "",
    val cached: String = "",
    val output: String = "",
    val cacheWrite: String = "",
    val subscription: Boolean = false,
) {
    fun validationError(): String? = if (listOf(input, cached, output, cacheWrite).any {
        it.isNotBlank() && (!it.matches(Regex("[0-9]{1,8}(\\.[0-9]{1,12})?")))
    }) "价格须为非负十进制数字，最多12位小数；留空表示未知" else null

    fun cost(usage: TokenUsage, separateCacheWrite: Boolean): BigDecimal? {
        val counts = quantities(usage, separateCacheWrite) ?: return null
        val amounts = counts.zip(listOf(input, cached, output, cacheWrite)).map { (tokens, price) ->
            if (tokens == 0L) BigDecimal.ZERO else
                price.toBigDecimalOrNull()?.multiply(BigDecimal.valueOf(tokens))?.divide(MILLION)
        }
        return amounts.takeIf { it.all { amount -> amount != null } }
            ?.fold(BigDecimal.ZERO) { sum, amount -> sum.add(requireNotNull(amount)) }
    }

    private fun quantities(usage: TokenUsage, separateCacheWrite: Boolean): List<Long>? {
        val hit = usage.cachedTokens
        val written = if (separateCacheWrite) usage.cacheWriteTokens else 0L
        val unknownCache = usage.cacheUnknownSamples != 0L || usage.cacheObservedInputTokens != usage.inputTokens
        return when {
            subscription || validationError() != null -> null
            !usageComplete(usage) -> null
            usage.inputTokens < 0 || usage.outputTokens < 0 -> null
            hit !in 0..usage.inputTokens -> null
            usage.inputTokens > 0 && unknownCache -> null
            written == null || written !in 0..(usage.inputTokens - hit) -> null
            else -> listOf(usage.inputTokens - hit - written, hit, usage.outputTokens, written)
        }
    }

    private fun usageComplete(usage: TokenUsage): Boolean = usage.inputObserved && usage.outputObserved

    fun toJson(): JSONObject = JSONObject().put("input", input).put("cached", cached)
        .put("output", output).put("cacheWrite", cacheWrite).put("subscription", subscription)

    companion object {
        private val MILLION = BigDecimal("1000000")
        fun fromJson(value: JSONObject?): ModelTokenPrices = if (value == null) ModelTokenPrices() else
            ModelTokenPrices(value.optString("input"), value.optString("cached"), value.optString("output"),
                value.optString("cacheWrite"), value.optBoolean("subscription"))
    }
}
