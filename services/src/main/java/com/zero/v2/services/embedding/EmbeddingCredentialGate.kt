package com.zero.v2.services.embedding

import java.io.IOException
import java.security.MessageDigest

class EmbeddingAuthorizationException : IOException("embedding_authorization_required")

/** Process-local circuit breaker. Key rotation reopens it without changing semantic vector identity. */
internal class EmbeddingCredentialGate {
    @Volatile private var rejected: String? = null

    fun stamp(identity: String, key: String?): String? = key?.let {
        MessageDigest.getInstance("SHA-256").digest("$identity\u0000$it".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun permits(stamp: String?): Boolean = stamp != null && stamp != rejected

    fun reject(stamp: String?) { rejected = stamp }
}
