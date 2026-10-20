package com.zero.v2.services.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStoreContractTest {
    @Test
    fun `credential seam supports fallback and deletion semantics`() {
        val store: CredentialStore = InMemoryCredentialStore()
        store.put("primary", "  secret  ")
        assertEquals("secret", store.getString("primary"))
        assertEquals("secret", store.getOrFallbackString("primary", "fallback"))

        store.put("fallback", "backup")
        assertEquals("backup", store.getOrFallbackString("missing", "fallback"))
        assertTrue(store.isConfigured("primary"))
        store.delete("primary")
        assertFalse(store.isConfigured("primary"))
    }

    private class InMemoryCredentialStore : CredentialStore {
        private val values = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { values[key] = value.trim() }
        override fun getString(key: String): String? = values[key]
        override fun getOrFallbackString(primaryKey: String, fallbackKey: String?): String? =
            values[primaryKey] ?: fallbackKey?.let(values::get)
        override fun delete(key: String) { values.remove(key) }
        override fun isConfigured(key: String): Boolean = values.containsKey(key)
    }
}
