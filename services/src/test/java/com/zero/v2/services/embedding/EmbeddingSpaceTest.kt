package com.zero.v2.services.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EmbeddingSpaceTest {
    @Test fun endpointModelAndInputContractHaveSeparateSpaces() {
        val original = EmbeddingSpace.id("https://a/v1|model", EmbeddingSpace.KNOWLEDGE)
        assertEquals(original, EmbeddingSpace.id("https://a/v1|model", EmbeddingSpace.KNOWLEDGE))
        assertNotEquals(original, EmbeddingSpace.id("https://b/v1|model", EmbeddingSpace.KNOWLEDGE))
        assertNotEquals(original, EmbeddingSpace.id("https://a/v1|model-v2", EmbeddingSpace.KNOWLEDGE))
        assertNotEquals(original, EmbeddingSpace.id("https://a/v1|model", EmbeddingSpace.MEMORY))
        assertNotEquals(original, EmbeddingSpace.id("https://a/v1|model", EmbeddingSpace.KNOWLEDGE + ":v2"))
        assertFalse(original.contains("https"))
    }
}
