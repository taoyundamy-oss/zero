package com.zero.v2.services.embedding

import java.security.MessageDigest

/** Versioned input contracts. Changing tokenization, chunking or templates invalidates old vectors. */
object EmbeddingSpace {
    const val MEMORY = "memory:text-first1000:v1"
    const val KNOWLEDGE = "knowledge:markdown300-600:title-heading-first1000:v1"

    fun id(providerIdentity: String, inputContract: String): String {
        val fields = listOf("embedding-space-v1", providerIdentity, inputContract,
            "tokenizer:provider", "dimensions:provider-default", "normalization:cosine", "index:float32-v1")
        val canonical = fields.joinToString("") { "${it.length}:$it" }
        return "space-v1:" + MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

fun EmbeddingProvider.spaceId(inputContract: String): String = EmbeddingSpace.id(identity, inputContract)
