package com.zero.v2.services.security

/** 凭据读取/写入窄 seam；具体加密存储由 SecureKeyStore 提供。 */
interface CredentialStore {
    /** In-process mutation generation. Odd values mean a write is still in progress. */
    val revision: Long get() = 0L
    val storageError: String? get() = null
    fun recoverStorage() = Unit
    fun put(
        key: String,
        value: String,
    )

    fun getString(key: String): String?

    fun getOrFallbackString(
        primaryKey: String,
        fallbackKey: String?,
    ): String?

    fun delete(key: String)

    fun isConfigured(key: String): Boolean
}
