package com.zero.v2.services.llm

import com.zero.v2.core.common.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ModelTokenPricesTest {
    private val usage = TokenUsage(inputTokens = 100, cachedTokens = 80, outputTokens = 10,
        totalTokens = 110, cacheObservedInputTokens = 100, cacheUnknownSamples = 0)

    @Test fun pricesDistinguishCacheAndPreserveDecimalPrecision() {
        val prices = ModelTokenPrices(input = "2", cached = "0.2", output = "8", cacheWrite = "3")
        assertEquals(0, "0.000136".toBigDecimal().compareTo(prices.cost(usage, false)))
        assertEquals(0, "0.000146".toBigDecimal().compareTo(prices.cost(usage.copy(cacheWriteTokens = 10), true)))
        assertNull(prices.cost(usage, true))
        assertNull(prices.cost(usage.copy(cacheUnknownSamples = 1), false))
        assertNull(prices.cost(usage.copy(outputObserved = false), false))
        assertNull(prices.copy(subscription = true).cost(usage, false))
        assertNull(ModelTokenPrices().cost(usage, false))
        assertEquals(0, ModelTokenPrices("0", "0", "0").cost(usage, false)!!.signum())
    }

    @Test fun pricingRoundTripsWithoutChangingProviderContinuity() {
        val original = LlmProfile.defaults().first()
        val priced = original.copy(tokenPrices = ModelTokenPrices("1.123456789012", "0.01", "5"))
        assertEquals(priced, LlmProfileJson.fromJson(LlmProfileJson.toJson(priced)))
        assertEquals(ProviderToolState.scope(original), ProviderToolState.scope(priced))
        assertEquals(original.tokenPrices, LlmProfileJson.fromJson(
            LlmProfileJson.toJson(original).apply { remove("tokenPrices") }).tokenPrices)
        for (invalid in listOf("-1", "NaN", "1e99", "0.0000000000001", "abc")) {
            assertNotNull(ModelTokenPrices(input = invalid).validationError())
        }
    }
}
