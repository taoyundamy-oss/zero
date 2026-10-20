package com.zero.v2.services.llm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingResponseDecoderTest {
    @Test fun embeddingUsageSupportsBothProtocolsWithoutInventingMissingCounts() {
        for (key in listOf("prompt_tokens", "input_tokens", "total_tokens")) {
            assertEquals(123L, EmbeddingResponseDecoder.usage("""{"usage":{"$key":123}}""", "fixture")!!.inputTokens)
        }
        for (body in listOf("{}", """{"usage":{"prompt_tokens":-1}}""", """{"usage":{"input_tokens":1.5}}""")) {
            assertNull(EmbeddingResponseDecoder.usage(body, "fixture"))
        }
    }
    @Test fun bothProtocolsRestoreShuffledRowsByIndex() {
        val compatible = """{"data":[{"index":1,"embedding":[0,1]},{"index":0,"embedding":[1,0]}]}"""
        val native = """{"output":{"embeddings":[
            {"text_index":1,"embedding":[0,1]},{"text_index":0,"embedding":[1,0]}]}}"""
        listOf(EmbeddingResponseDecoder.decode(compatible, 2, false),
            EmbeddingResponseDecoder.decode(native, 2, true)).forEach {
            assertArrayEquals(floatArrayOf(1f, 0f), it[0], 0f)
            assertArrayEquals(floatArrayOf(0f, 1f), it[1], 0f)
        }
    }

    @Test fun duplicateMissingFractionalAndOutOfRangeIndexesRejectWholeBatch() {
        listOf("1", "0.5", "3", "null").forEach { index ->
            val body = """{"data":[{"index":1,"embedding":[0,1]},{"index":$index,"embedding":[1,0]}]}"""
            assertTrue(EmbeddingResponseDecoder.decode(body, 2, false).all { it == null })
        }
        assertTrue(EmbeddingResponseDecoder.decode("""{"data":[]} """, 2, false).all { it == null })
    }

    @Test fun invalidComponentsDoNotBecomeZeroValuedVectors() {
        listOf("[1,\"wrong\"]", "[0,0]", "[1,null]", "[]").forEach { vector ->
            val body = """{"data":[{"index":0,"embedding":$vector}]}"""
            assertNull(EmbeddingResponseDecoder.decode(body, 1, false).single())
        }
    }
}
