package com.zero.v2.services.termux

import com.zero.v2.core.common.ModuleReleaseManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseManifestWireTest {
    @Test fun `all declaration fields survive wire encoding with structured schemas`() {
        val manifest = ModuleReleaseManifest(capabilities = listOf("lookup"), dependencies = listOf("requests==2.31.0"),
            termuxDeps = listOf("ffmpeg"), inputSchema = """{"type":"object"}""",
            outputSchema = """{"type":"string"}""", resourceLimits = """{"maxCalls":5}""", transport = "socket")
        val wire = ReleaseManifestWire.encode(manifest)
        assertEquals(12, wire.length())
        assertEquals("lookup", wire.getJSONArray("capabilities").getString(0))
        assertEquals("requests==2.31.0", wire.getJSONArray("deps").getString(0))
        assertEquals("ffmpeg", wire.getJSONArray("termuxDeps").getString(0))
        assertEquals("object", wire.getJSONObject("inputSchema").getString("type"))
        assertEquals("string", wire.getJSONObject("outputSchema").getString("type"))
        assertEquals(5, wire.getJSONObject("resourceLimits").getInt("maxCalls"))
        assertEquals("socket", wire.getString("transport"))
        assertEquals(15, wire.getInt("probe_timeout_s"))
        assertEquals(manifest.healthContract, wire.getString("healthContract"))
        assertEquals(manifest.sdkRange, wire.getString("sdkRange"))
        assertEquals(manifest.entrypoint, wire.getString("entrypoint"))
        assertEquals(1, wire.getInt("dataSchemaVersion"))
    }

    @Test fun `malformed declarations are rejected instead of silently falling back`() {
        val bad = listOf(ModuleReleaseManifest(inputSchema = "[]"), ModuleReleaseManifest(outputSchema = "broken"),
            ModuleReleaseManifest(resourceLimits = "null"), ModuleReleaseManifest(dataSchemaVersion = 0),
            ModuleReleaseManifest(healthContract = "anything15"),
            ModuleReleaseManifest(healthContract = "endpoint_window_999s"),
            ModuleReleaseManifest(transport = "shell"))
        bad.forEach { manifest ->
            assertTrue("must reject $manifest", runCatching { ReleaseManifestWire.encode(manifest) }.isFailure)
        }
    }
}
