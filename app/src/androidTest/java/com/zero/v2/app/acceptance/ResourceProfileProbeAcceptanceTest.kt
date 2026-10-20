package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Read-only common routing proof on both signed debug and benchmark APKs. */
class ResourceProfileProbeAcceptanceTest {
    @Test fun verifyLocalResourceFixture() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("resourceFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("zero_llm_profiles", 0)
        val snapshot = prefs.getString("snapshot_json", null)?.let(::JSONObject)
        val legacy = JSONArray(prefs.getString("profiles_json", "[]"))
        val legacyProfiles = (0 until legacy.length()).map { legacy.getJSONObject(it) }
        val legacyLocal = legacyProfiles.single { it.optString("id") == "local-qwen" }
        assertEquals(listOf("local-qwen"), legacyProfiles.filter {
            it.optString("kind", "CHAT") == "CHAT" && it.optBoolean("enabled", true)
        }.map { it.getString("id") })
        assertEquals(24576, legacyLocal.getInt("contextWindow"))
        assertEquals(2048, legacyLocal.getInt("maxTokens"))
        assertEquals("local-qwen", legacyLocal.getString("modelId"))
        assertEquals("http://127.0.0.1:8080/v1", legacyLocal.getString("baseUrl"))
        assertFalse(legacyLocal.getBoolean("enableThinking"))
        assertEquals(1000, legacyLocal.getInt("priority"))
        val rows = snapshot?.getJSONArray("profiles") ?: JSONArray(prefs.getString("profiles_json", "[]"))
        val profiles = (0 until rows.length()).map { rows.getJSONObject(it) }
        val local = profiles.single { it.optString("id") == "local-qwen" }
        val connection = if (snapshot != null) {
            val connections = snapshot.getJSONArray("connections")
            (0 until connections.length()).map { connections.getJSONObject(it) }
                .single { it.getString("id") == local.getString("connectionId") }
        } else local
        assertEquals(listOf("local-qwen"), profiles.filter {
            it.optString("kind", "CHAT") == "CHAT" && it.optBoolean("enabled", true)
        }.map { it.getString("id") })
        assertEquals(24576, local.getInt("contextWindow"))
        assertEquals(2048, local.getInt("maxTokens"))
        assertEquals("local-qwen", local.getString("modelId"))
        assertEquals("http://127.0.0.1:8080/v1", connection.getString("baseUrl"))
        assertFalse(local.getBoolean("enableThinking"))
        assertEquals(1000, local.getInt("priority"))
        assertTrue(local.getBoolean("enabled"))
        val tag = args.getString("resourceProbeTag") ?: "untagged"
        require(tag.matches(Regex("[a-zA-Z0-9-]+")))
        val report = JSONObject().put("localOnlyChat", true).put("contextWindow", 24576)
            .put("outputReserve", 2048).put("thinking", false).put("priority", 1000)
        File(context.getExternalFilesDir(null), "a9-profile-$tag.json").writeText(report.toString(2))
    }
}
