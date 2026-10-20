package com.zero.v2.services.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureKeyStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CredentialStore {
        private val generation = java.util.concurrent.atomic.AtomicLong()
        override val revision: Long get() = generation.get()
        companion object {
            private const val TAG = "SecureKeyStore"
            private const val PREFS_NAME = "zero_v2_keys"
        }

        private val state by lazy { context.getSharedPreferences("zero_credential_storage", Context.MODE_PRIVATE) }
        private val gate = CredentialStorageGate {
            val old = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!state.getBoolean("encrypted_v2", false)) {
                check(old.all.isEmpty() || old.all.keys.any { it.startsWith("__androidx_security_crypto_") }) {
                    "旧明文凭据待迁移，原数据保留"
                }
            }
            encrypted(if (state.getBoolean("encrypted_v2", false)) "zero_v2_keys_encrypted_v2" else PREFS_NAME)
        }
        override val storageError: String? get() = gate.error

        private fun encrypted(name: String): SharedPreferences {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(context, name, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }

        /** Explicit settings action; no deletion, key regeneration, or plaintext fallback. */
        @Synchronized
        override fun recoverStorage() {
            generation.incrementAndGet()
            try {
                val old = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
                if (!state.getBoolean("encrypted_v2", false) && old.isNotEmpty() &&
                    old.keys.none { it.startsWith("__androidx_security_crypto_") }) {
                    migrateLegacyCredentials(old, encrypted("zero_v2_keys_encrypted_v2")) {
                        state.edit().putBoolean("encrypted_v2", true).commit()
                    }
                }
                gate.invalidate()
                gate.access { it.all }
            } finally { generation.incrementAndGet() }
        }

        /** Commit failures must be visible to configuration owners; restore the local cache before returning. */
        private fun writeConfirmed(key: String, value: String?) {
            gate.access { writePreferenceConfirmed(it, key, value, "Credential could not be saved; retry required") }
        }

        @Synchronized
        override fun put(
            key: String,
            value: String,
        ) {
            generation.incrementAndGet()
            try {
                writeConfirmed(key, value.trim())
            } finally { generation.incrementAndGet() }
        }

        override fun getString(key: String): String? {
            val v = gate.access { prefs ->
                val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                check(state.getBoolean("encrypted_v2", false) || !raw.contains(key)) { "旧混合凭据需恢复，原件保留" }
                prefs.getString(key, null)
            }
            Log.i(TAG, "get($key) -> configured=${!v.isNullOrBlank()}")
            return v
        }

        override fun getOrFallbackString(
            primaryKey: String,
            fallbackKey: String?,
        ): String? {
            getString(primaryKey)?.let { return it }
            if (fallbackKey != null) return getString(fallbackKey)
            return null
        }

        @Synchronized
        override fun delete(key: String) {
            generation.incrementAndGet()
            try { writeConfirmed(key, null) } finally { generation.incrementAndGet() }
        }

        override fun isConfigured(key: String): Boolean = !getString(key).isNullOrBlank()
    }
