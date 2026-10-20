package com.zero.v2.services.llm

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor

/** Passive HTTP observations only: never probes, consumes a body, or certifies generated content. */
@Singleton
class ModelRequestHealth @Inject constructor() {
    class Identity internal constructor(val profileId: String, internal val digest: String, val operation: String)
    data class Observation(val operation: String, val httpStatus: Int?, val outcome: String, val observedAt: Long)
    private data class Record(val identity: Identity, val sequence: Long, val observation: Observation)
    private val sequence = AtomicLong()
    private val records = linkedMapOf<String, Record>()

    fun identity(profile: LlmProfile, key: String?, operation: String): Identity {
        val configuration = profile.toString()
        val bytes = "$configuration\u0000$key".toByteArray(Charsets.UTF_8)
        val digest = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        return Identity(profile.id, digest, operation)
    }

    @Synchronized
    fun observation(identity: Identity?): Observation? = identity?.let {
        records[it.profileId]?.takeIf { record -> record.identity.digest == it.digest }?.observation
    }

    @Synchronized
    private fun record(identity: Identity, order: Long, code: Int?, outcome: String) {
        if ((records[identity.profileId]?.sequence ?: -1L) > order) return
        records.remove(identity.profileId)
        records[identity.profileId] = Record(identity, order,
            Observation(identity.operation, code, outcome, System.currentTimeMillis()))
        while (records.size > MAX_PROFILES) records.remove(records.keys.first())
    }

    fun interceptor() = Interceptor { chain ->
        val identity = chain.request().tag(Identity::class.java)
        if (identity == null) return@Interceptor chain.proceed(chain.request())
        val order = sequence.incrementAndGet()
        try {
            chain.proceed(chain.request()).also { response ->
                if (!chain.call().isCanceled()) record(identity, order, response.code, "http_${response.code}")
            }
        } catch (error: IOException) {
            if (!chain.call().isCanceled()) record(identity, order, null, "transport_error")
            throw error
        }
    }

    private companion object { const val MAX_PROFILES = 128 }
}
