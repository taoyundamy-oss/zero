package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModuleReleaseManifest] + [contentHash] 单测 — E1 验收：版本化 manifest + 确定性 hash。
 */
class ModuleReleaseManifestTest {

    @Test
    fun contentHash_deterministic_sameCodeManifest() {
        val m = ModuleReleaseManifest(entrypoint = "serve", dependencies = listOf("requests==2.31.0"))
        val code = "def serve(): pass"
        assertEquals(contentHash(code, m), contentHash(code, m))
    }

    @Test
    fun contentHash_differsOnCodeChange() {
        val m = ModuleReleaseManifest()
        assertNotEquals(contentHash("def serve(): pass", m), contentHash("def serve(): return 1", m))
    }

    @Test
    fun contentHash_differsOnManifestChange() {
        val code = "def serve(): pass"
        val m1 = ModuleReleaseManifest(entrypoint = "serve")
        val m2 = ModuleReleaseManifest(entrypoint = "run")
        assertNotEquals(contentHash(code, m1), contentHash(code, m2))
    }

    @Test
    fun contentHash_stableOnDependencyOrder() {
        // manifest.canonicalString 把 dependencies 排序——重排不改 hash（确定性）
        val code = "pass"
        val m1 = ModuleReleaseManifest(dependencies = listOf("a==1", "b==2"))
        val m2 = ModuleReleaseManifest(dependencies = listOf("b==2", "a==1"))
        assertEquals(contentHash(code, m1), contentHash(code, m2))
    }

    @Test
    fun contentHash_stableOnCapabilitiesOrder() {
        val code = "pass"
        val m1 = ModuleReleaseManifest(capabilities = listOf("search", "summarize"))
        val m2 = ModuleReleaseManifest(capabilities = listOf("summarize", "search"))
        assertEquals(contentHash(code, m1), contentHash(code, m2))
    }

    @Test
    fun canonicalString_hasAllFixedFields() {
        // 设计§5 固定字段全在
        val s = ModuleReleaseManifest(
            sdkRange = ">=1.0", entrypoint = "serve", capabilities = listOf("x"),
            dependencies = listOf("a==1"), termuxDeps = listOf("ffmpeg"),
            inputSchema = "{\"a\":1}", outputSchema = "{}", healthContract = "endpoint_window_15s",
            resourceLimits = "{}", dataSchemaVersion = 2, transport = "http",
        ).canonicalString()
        listOf("sdkRange=", "entrypoint=", "capabilities=", "dependencies=", "termuxDeps=",
            "inputSchema=", "outputSchema=", "healthContract=", "resourceLimits=",
            "dataSchemaVersion=", "transport=").forEach {
            assertTrue("缺字段 $it: $s", s.contains(it))
        }
    }

    @Test
    fun contentHash_isHexSha256() {
        val h = contentHash("pass", ModuleReleaseManifest())
        assertEquals(64, h.length)  // SHA-256 hex = 64
        assertTrue(h.all { it in "0123456789abcdef" })
    }
}
