package com.zero.v2.services.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpEndpointTest {
    @Test fun explicitAmapKeyIsKeptOutOfStoredUrlAndPreservesOtherQueryParameters() {
        val server = McpServerConfig("owned", "Renamed", "https://mcp.amap.com/mcp?mode=one&key=old&key=duplicate")
        val stored = McpEndpoint.forStorage(server, true)
        assertEquals("https://mcp.amap.com/mcp?mode=one", stored.url)
        assertEquals("https://mcp.amap.com/mcp?mode=one&key=new%26key", McpEndpoint.forTransport(stored, "new&key"))
        assertEquals(server, McpEndpoint.forStorage(server, false))
    }

    @Test fun arbitraryServerIsNotGivenAmapQueryAndDisplayOmitsSensitiveParts() {
        val server = McpServerConfig("owned", "高德", "https://example.com/mcp?key=existing")
        assertEquals(server, McpEndpoint.forStorage(server, true))
        assertEquals(server.url, McpEndpoint.forTransport(server, "new"))
        assertEquals("https://example.com/mcp",
            McpEndpoint.display("https://user:secret@example.com/mcp?token=owned#secret"))
    }

    @Test fun onlyPlainEndpointsCanEnterSavedDrafts() {
        assertTrue(McpEndpoint.canSaveDraft("https://example.com/mcp"))
        assertTrue(McpEndpoint.canSaveDraft(""))
        for (url in listOf("https://example.com/mcp?anything=secret", "https://u:p@example.com/mcp",
            "https://example.com/mcp#secret", "unparsed-secret")) assertFalse(McpEndpoint.canSaveDraft(url))
    }
}
