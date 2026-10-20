package com.zero.v2.services.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePersistenceTest {
    private val first = LlmProfile.defaults().first().copy(
        modelId = "user-model", apiKeyRefFallback = "old-ref", capabilities = emptySet(),
    )
    private fun legacy() = JSONArray().put(LlmProfileJson.toJson(first))
        .put(LlmProfileJson.toJson(first.copy(id = "another"))).toString()

    @Test fun `migration preserves values and separates connections and is idempotent`() {
        var saved = ""
        val owner = ProfilePersistence(null, legacy()) { saved = it; true }
        assertNull(owner.error)
        assertEquals(first, owner.profiles.first().copy(connectionId = ""))
        assertEquals(2, owner.profiles.map { it.connectionId }.distinct().size)
        val root = JSONObject(saved)
        assertFalse(root.getJSONArray("profiles").getJSONObject(0).has("baseUrl"))
        var writes = 0
        val reopened = ProfilePersistence(saved, "corrupt old data") { writes++; true }
        assertEquals(owner.profiles, reopened.profiles)
        assertEquals(0, writes)
    }

    @Test fun `bad data and unknown versions never write defaults`() {
        for (raw in listOf("invalid", "{\"schemaVersion\":999}")) {
            var writes = 0
            val owner = ProfilePersistence(raw, legacy()) { writes++; true }
            assertNotNull(owner.error)
            assertEquals(0, writes)
            assertTrue(runCatching { owner.save(listOf(first)) }.isFailure)
        }
        var writes = 0
        val corruptLegacy = ProfilePersistence(null, "invalid") { writes++; true }
        assertNotNull(corruptLegacy.error)
        assertEquals(0, writes)
    }

    @Test fun `failed writes retain published profiles and freeze further writes`() {
        val owner = ProfilePersistence(ProfileSnapshot.encode(listOf(first)), null) { false }
        val before = owner.profiles
        assertTrue(runCatching { owner.save(listOf(first.copy(modelId = "changed"))) }.isFailure)
        assertEquals(before, owner.profiles)
        assertNotNull(owner.error)
    }

    @Test fun `missing references duplicate identities and conflicting shared connections fail`() {
        val raw = JSONObject(ProfileSnapshot.encode(listOf(first)))
        raw.put("connections", JSONArray())
        assertTrue(runCatching { ProfileSnapshot.decode(raw.toString()) }.isFailure)
        assertTrue(runCatching { ProfileSnapshot.encode(listOf(first, first)) }.isFailure)
        assertTrue(runCatching { ProfileSnapshot.encode(listOf(
            first.copy(connectionId = "shared"),
            first.copy(id = "other", connectionId = "shared", baseUrl = "https://other.example/v1"),
        )) }.isFailure)
    }

    @Test fun `no auth omits credentials and header auth is explicit`() {
        val local = first.copy(authMode = ConnectionAuth.NONE)
        assertTrue(CloudflareChatTransport.credentialReady(local, null))
        assertNull(CloudflareChatTransport.request(local, null).build().header("Authorization"))
        assertFalse(CloudflareChatTransport.credentialReady(first, ""))
        val header = first.copy(authMode = ConnectionAuth.HEADER, authHeader = "x-api-key")
        val request = CloudflareChatTransport.request(header, "secret").build()
        assertEquals("secret", request.header("x-api-key"))
        assertNull(request.header("Authorization"))
        assertEquals("plain", ProviderErrorMessage.redacted("{\"error\":{\"message\":\"plain\"}}", null))
    }

    @Test fun `non chat profiles retain their original protocol and fields`() {
        val profiles = LlmProfile.defaults().filter { it.kind != LlmProfile.Kind.CHAT }
        assertEquals(profiles, ProfileSnapshot.decode(ProfileSnapshot.encode(profiles)))
    }
    @Test fun `unknown capability and throwing backend preserve data without default replacement`() {
        val raw = LlmProfileJson.toJson(first).put("capabilities", JSONArray().put("FUTURE_CAPABILITY"))
        var writes = 0
        val owner = ProfilePersistence(null, JSONArray().put(raw).toString()) { writes++; true }
        assertNotNull(owner.error)
        assertEquals(0, writes)
        val failedMigration = ProfilePersistence(null, legacy()) { throw java.io.IOException("disk failed") }
        assertTrue(failedMigration.error.orEmpty().contains("写入失败"))
        assertEquals(first.modelId, failedMigration.profiles.first().modelId)
    }
}
