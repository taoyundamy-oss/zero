package com.zero.v2.services.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class McpCredentialReferenceTest {
    @Test fun sameDisplayNameCannotShareNewCredentials() {
        val first = McpServerConfig("connection-one", "Same name", "https://one.example/mcp")
        val second = first.copy(id = "connection-two", url = "https://two.example/mcp")
        assertNotEquals(McpCredentialReference.forWrite(first, true), McpCredentialReference.forWrite(second, true))
    }

    @Test fun renameAndBlankKeyPreserveExistingReference() {
        val server = McpServerConfig("connection-one", "Renamed", "https://one.example/mcp", "legacy-reference")
        assertEquals("legacy-reference", McpCredentialReference.forWrite(server, false))
        assertEquals("legacy-reference", McpCredentialReference.forWrite(server, true))
        assertEquals("", McpCredentialReference.forWrite(server.copy(apiKeyRef = ""), false))
    }

    @Test fun sharedLegacyKeyForksOnlyTheExplicitlyEditedConnection() {
        val first = McpServerConfig("connection-one", "Same name", "https://one.example/mcp", "legacy-shared")
        val second = first.copy(id = "connection-two")
        val connections = listOf(first, second)
        val credentials = mutableMapOf("legacy-shared" to "owned-old-value")
        assertEquals("legacy-shared", McpCredentialReference.forWrite(first, false, connections))
        val updatedRef = McpCredentialReference.forWrite(first, true, connections)
        credentials[updatedRef] = "owned-new-value"
        assertNotEquals("legacy-shared", updatedRef)
        assertEquals("owned-old-value", credentials[second.apiKeyRef])
        assertEquals("owned-new-value", credentials[updatedRef])
        assertEquals(updatedRef, McpCredentialReference.forWrite(first.copy(apiKeyRef = updatedRef), true, connections))
    }

    @Test fun anExistingReferenceCannotBeReusedForAnotherOwner() {
        val first = McpServerConfig("connection-one", "First", "https://one.example/mcp")
        val second = first.copy(id = "connection-two", apiKeyRef = "mcp_server:connection-one")
        assertNotEquals(second.apiKeyRef, McpCredentialReference.forWrite(first, true, listOf(first, second)))
    }
}
