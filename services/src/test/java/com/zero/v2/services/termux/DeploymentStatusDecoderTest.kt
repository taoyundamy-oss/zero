package com.zero.v2.services.termux

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeploymentStatusDecoderTest {
    @Test fun `recovering supervisor is alive but partial runtime is not authoritative`() {
        val raw = JSONObject("""{"alive":true,"pid":12,"ready":false,"phase":"recovering",
            "modules":[{"id":"demo","state":"CRASHED"}]}""")
        val pending = DeploymentStatusDecoder.health(raw, 7878, 100)
        assertTrue(pending.alive)
        assertNull(pending.runtimeModules)
        raw.put("ready", true)
        assertEquals("CRASHED", DeploymentStatusDecoder.health(raw, 7878, 101).runtimeModules!!.single().state)
    }
    @Test fun `missing authority is unknown and explicit empty is known`() {
        assertEquals("supervisor_unavailable", DeploymentStatusDecoder.deployment("demo", null, 100).errorCode)
        assertEquals("invalid_status_reply", DeploymentStatusDecoder.deployment("demo",
            JSONObject("""{"ok":true,"history":[]}"""), 100).errorCode)
        val empty = DeploymentStatusDecoder.deployment("demo",
            JSONObject("""{"ok":true,"active":null,"history":[],"emergency":false}"""), 100)
        assertNull(empty.errorCode)
        assertNull(empty.active)
        assertEquals(100L, empty.observedAt)
        assertEquals(false, empty.emergencyStopped)
    }

    @Test fun `authority metadata is allowlisted and cross module rows rejected`() {
        val row = JSONObject().put("module_id", "demo").put("deployment_id", "a".repeat(64))
            .put("content_hash", "b".repeat(64)).put("state", "ACTIVE")
            .put("created_at", 10).put("updated_at", 20).put("config", "PRIVATE_CONFIG")
        val reply = JSONObject().put("ok", true).put("active", row).put("history", org.json.JSONArray().put(row))
        val status = DeploymentStatusDecoder.deployment("demo", reply, 100)
        assertEquals("ACTIVE", status.active?.state)
        assertEquals(20L, status.active?.updatedAt)
        assertTrue(status.recent.isEmpty())
        assertFalse(status.toString().contains("PRIVATE_CONFIG"))
        assertEquals("invalid_status_reply", DeploymentStatusDecoder.deployment("other", reply, 100).errorCode)
    }

    @Test fun `health retains unknown SDK without exposing endpoint or raw states`() {
        val health = DeploymentStatusDecoder.health(JSONObject("""{"alive":true,"pid":12,"rootfs":true,
            "release_protocol":1,"modules":[{"id":"demo","state":"ACTIVE","endpoint":"PRIVATE_URL"},
            {"id":"other","state":"PRIVATE_ERROR"}]}"""), 7878, 100)
        assertNull(health.sdkVersion)
        assertEquals(1, health.releaseProtocol)
        assertEquals(listOf("ACTIVE", "UNKNOWN"), health.runtimeModules!!.map { it.state })
        assertFalse(health.toString().contains("PRIVATE"))
        assertNull(DeploymentStatusDecoder.health(JSONObject("""{"alive":true}"""), 7878, 100).runtimeModules)
    }
}
