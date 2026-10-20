package com.zero.v2.services.termux

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorBundleTest {
    @Test fun `recovery readiness prevents premature bundle acceptance while old hosts remain compatible`() {
        assertFalse(SupervisorBundle.isReady(null))
        assertTrue(SupervisorBundle.isReady(JSONObject()))
        assertTrue(SupervisorBundle.isReady(JSONObject().put("ready", true)))
        assertFalse(SupervisorBundle.isReady(JSONObject().put("ready", false)))
        assertFalse(SupervisorBundle.isReady(JSONObject().put("ready", "true")))
        val hashes = SupervisorBundle.hashes(SupervisorBundle.files.associateWith { it.toByteArray() })
        val health = JSONObject().put("release_protocol", 1).put("asset_hashes", hashes)
            .put("installed_asset_hashes", hashes).put("debian_sdk_hash", hashes.getString("zero_tool.py"))
            .put("ready", false)
        assertFalse(SupervisorBundle.matches(health, hashes))
        health.put("ready", true)
        assertTrue(SupervisorBundle.matches(health, hashes))
    }
    @Test fun `old protocol or one changed installed side requires upgrade`() {
        val hashes = SupervisorBundle.hashes(SupervisorBundle.files.associateWith { it.toByteArray() })
        assertFalse(SupervisorBundle.matches(JSONObject().put("release_protocol", 1), hashes))
        val health = JSONObject().put("release_protocol", 1).put("asset_hashes", hashes)
            .put("installed_asset_hashes", hashes)
            .put("debian_sdk_hash", hashes.getString("zero_tool.py"))
        assertTrue(SupervisorBundle.matches(health, hashes))
        assertFalse(SupervisorBundle.matches(JSONObject(health.toString()).put("debian_sdk_hash", "old"), hashes))
        val stale = JSONObject(health.toString())
        stale.getJSONObject("asset_hashes").put("zero_release.py", "old")
        assertFalse(SupervisorBundle.matches(stale, hashes))
        val diskChanged = JSONObject(health.toString())
        diskChanged.getJSONObject("installed_asset_hashes").put("zero_supervisor.py", "old")
        assertFalse(SupervisorBundle.matches(diskChanged, hashes))
    }
}
