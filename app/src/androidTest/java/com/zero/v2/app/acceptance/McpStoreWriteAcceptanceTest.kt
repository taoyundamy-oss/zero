package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.mcp.McpServerConfig
import com.zero.v2.services.mcp.McpServerStore
import com.zero.v2.services.security.CredentialStore
import dagger.hilt.android.EntryPointAccessors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Simulates commit's memory-before-disk semantics; only an owned in-memory preference backend is used. */
class McpStoreWriteAcceptanceTest {
    @Test fun staleEditorCannotReinsertDeletedOwnerOrWriteCredential() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = RejectingPreferences("[]")
        val owned = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = backend.preferences
        }
        val traces = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java).traces()
        val store = McpServerStore(owned, traces)
        val original = McpServerConfig("owned_stale", "Owned", "https://owned.example/mcp", apiKeyRef = "owned_ref")
        store.upsert(original)
        val keys = OwnedCredentials()
        keys.put("owned_ref", "old")
        store.remove(original.id)
        try { store.saveWithCredential(original.copy(name = "Stale draft"), "new", keys, expected = original)
            fail("Stale editor must reject deleted owner") } catch (_: IllegalStateException) { }
        assertTrue(store.servers.value.isEmpty())
        assertEquals("old", keys.getString("owned_ref"))
    }

    @Test fun credentialOwnerCompensatesRejectedConfigurationAndPreservesOtherOwners() {
        val original = McpServerConfig("a8_credential", "Owned", "https://original.example/mcp", apiKeyRef = "owned_ref")
        val initial = JSONArray().put(JSONObject().put("id", original.id).put("name", original.name)
            .put("url", original.url).put("apiKeyRef", original.apiKeyRef).put("enabled", true)).toString()
        val backend = RejectingPreferences(initial)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owned = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = backend.preferences
        }
        val traces = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java).traces()
        val store = McpServerStore(owned, traces)
        val credentials = OwnedCredentials()
        credentials.put("owned_ref", "owned-old")
        credentials.put("other_ref", "other-old")
        backend.reject = true
        try { store.saveWithCredential(original.copy(name = "Changed"), "owned-new", credentials)
            fail("Rejected configuration must fail") } catch (_: IllegalStateException) { }
        assertEquals("owned-old", credentials.getString("owned_ref"))
        assertEquals("other-old", credentials.getString("other_ref"))
        assertEquals(listOf(original), store.servers.value)
        val newServer = McpServerConfig("a8_new", "Owned", "https://new.example/mcp")
        try { store.saveWithCredential(newServer, "owned-new", credentials)
            fail("Rejected new connection must fail") } catch (_: IllegalStateException) { }
        assertNull(credentials.getString("mcp_server:a8_new"))
        backend.reject = false
        store.saveWithCredential(original.copy(name = "Changed"), "owned-new", credentials)
        assertEquals("owned-new", credentials.getString("owned_ref"))
        store.saveWithCredential(store.getById(original.id)!!.copy(name = "Renamed"), " ", credentials)
        assertEquals("owned-new", credentials.getString("owned_ref"))
        assertEquals("other-old", credentials.getString("other_ref"))
        assertEquals("owned_ref", McpServerStore(owned, traces).getById(original.id)!!.apiKeyRef)
    }

    @Test fun rejectedStorageCannotPublishOrReloadCandidateAndCanRetry() {
        val original = McpServerConfig("a8_owned", "Original", "https://original.example/mcp")
        val initial = JSONArray().put(JSONObject().put("id", original.id).put("name", original.name)
            .put("url", original.url).put("enabled", true)).toString()
        val backend = RejectingPreferences(initial)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owned = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = backend.preferences
        }
        val traces = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java).traces()
        val store = McpServerStore(owned, traces)
        val candidate = original.copy(name = "Updated", url = "https://updated.example/mcp")
        backend.reject = true
        var rejected = false
        try { store.upsert(candidate) } catch (_: IllegalStateException) { rejected = true }
        assertTrue("Rejected preference write must report failure", rejected)
        assertEquals(listOf(original), store.servers.value)
        assertEquals(original.url, JSONArray(backend.disk.getValue("servers_json")).getJSONObject(0).getString("url"))
        assertEquals(listOf(original), McpServerStore(owned, traces).servers.value)
        backend.reject = false
        store.upsert(candidate)
        assertEquals(listOf(candidate), store.servers.value)
        backend.reject = true
        try { store.remove(candidate.id); fail("Rejected removal must fail") } catch (_: IllegalStateException) { }
        try { store.toggle(candidate.id); fail("Rejected toggle must fail") } catch (_: IllegalStateException) { }
        assertEquals(listOf(candidate), store.servers.value)
        assertEquals(listOf(candidate), McpServerStore(owned, traces).servers.value)
    }
}

internal class OwnedCredentials : CredentialStore {
    private val entries = mutableMapOf<String, String>()
    override fun put(key: String, value: String) { entries[key] = value }
    override fun getString(key: String) = entries[key]
    override fun getOrFallbackString(primaryKey: String, fallbackKey: String?) = entries[primaryKey] ?: entries[fallbackKey]
    override fun delete(key: String) { entries.remove(key) }
    override fun isConfigured(key: String) = key in entries
}

internal class RejectingPreferences(initial: String) {
    var reject = false
    var memory = mutableMapOf("servers_json" to initial)
    var disk = memory.toMap()
    val preferences: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getString" -> memory[args!![0] as String] ?: args[1]
            "getAll" -> memory.toMap()
            "contains" -> memory.containsKey(args!![0])
            "edit" -> editor()
            "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
            else -> throw UnsupportedOperationException(method.name)
        }
    } as SharedPreferences

    private fun editor(): SharedPreferences.Editor {
        val pending = mutableMapOf<String, String?>()
        return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1] as String?; proxy }
                "remove" -> { pending[args!![0] as String] = null; proxy }
                "commit", "apply" -> {
                    pending.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
                    if (!reject) disk = memory.toMap()
                    if (method.name == "commit") !reject else null
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences.Editor
    }
}
