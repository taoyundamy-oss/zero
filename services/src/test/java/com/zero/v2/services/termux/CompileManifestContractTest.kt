package com.zero.v2.services.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CompileManifestContractTest {
    private val parser = CompileManager()

    @Test fun `socket source without explicit entry retains socket startup convention`() {
        val manifest = parser.parseManifest("# ZERO_MANIFEST: {\"transport\":\"socket\"}")!!
        assertEquals("serve_socket", manifest.release.entrypoint)
        assertEquals("socket", manifest.release.transport)
    }

    @Test fun `source declarations survive production conversion and wire encoding`() {
        val source = """# ZERO_MANIFEST: {"deps":["requests==2.31.0"],"termuxDeps":["ffmpeg"],
            "capabilities":["lookup"],"sdkRange":">=1.0,<2.0","entrypoint":"serve_custom",
            "inputSchema":{"type":"object"},"outputSchema":{"type":"string"},
            "resourceLimits":{"maxCalls":5},"healthContract":"endpoint_window_30s",
            "dataSchemaVersion":2,"transport":"socket","configSchema":[]}""".replace("\n", "")
        val parsed = parser.parseManifest(source)!!
        val wire = ReleaseManifestWire.encode(parsed.toReleaseManifest(60))
        assertEquals("lookup", wire.getJSONArray("capabilities").getString(0))
        assertEquals("requests==2.31.0", wire.getJSONArray("deps").getString(0))
        assertEquals("ffmpeg", wire.getJSONArray("termuxDeps").getString(0))
        assertEquals(">=1.0,<2.0", wire.getString("sdkRange"))
        assertEquals("serve_custom", wire.getString("entrypoint"))
        assertEquals("object", wire.getJSONObject("inputSchema").getString("type"))
        assertEquals("string", wire.getJSONObject("outputSchema").getString("type"))
        assertEquals(5, wire.getJSONObject("resourceLimits").getInt("maxCalls"))
        assertEquals(30, wire.getInt("probe_timeout_s"))
        assertEquals(2, wire.getInt("dataSchemaVersion"))
        assertEquals("socket", wire.getString("transport"))
    }

    @Test fun `legacy sources retain configured health timeout and empty contracts`() {
        for (source in listOf("print(1)", "# ZERO_MANIFEST: {\"deps\":[],\"runtime\":\"python\"}")) {
            val wire = ReleaseManifestWire.encode(parser.parseManifest(source)!!.toReleaseManifest(60))
            assertEquals(60, wire.getInt("probe_timeout_s"))
            assertEquals("http", wire.getString("transport"))
            assertEquals(0, wire.getJSONObject("inputSchema").length())
        }
        assertNotNull(parser.parseManifest("# ZERO_MANIFEST: {\"inputSchema\":\"{}\"}"))
    }

    @Test fun `malformed explicit declarations never silently become defaults`() {
        for (json in listOf("{\"deps\":\"requests\"}", "{\"capabilities\":[1]}",
            "{\"inputSchema\":[]}", "{\"resourceLimits\":null}", "{\"dataSchemaVersion\":1.5}",
            "{\"configSchema\":{}}", "{\"healthContract\":\"invalid15\"}", "{\"transport\":\"shell\"}")) {
            assertNull(json, parser.parseManifest("# ZERO_MANIFEST: $json"))
        }
    }
}
