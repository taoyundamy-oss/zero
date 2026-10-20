package com.zero.v2.services.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileManagementTest {
    private val first = LlmProfile("one", "One", "https://example.test/v1", "secret-ref",
        modelId = "wire", connectionId = "shared", enabled = false)

    @Test fun `shared edit revalidates protocol of every affected model`() {
        val luna = first.copy(baseUrl = "https://api.cloudflare.com/client/v4/accounts/a/ai/v1",
            modelId = "openai/gpt-6-luna", contractId = "cf-luna-responses-v1",
            gatewayMode = GatewayMode.CF_GATEWAY, cloudflareGatewayId = "default")
        val other = luna.copy(id = "other", contractId = "generic-v1")
        val changed = other.copy(baseUrl = "https://gateway.ai.cloudflare.com/v1/a/default/compat")
        assertTrue(runCatching {
            ProfileManagement.apply(listOf(luna, other), ProfileEdit.Save(changed, true))
        }.isFailure)
    }

    @Test fun `new drafts do not enable routing or invent tool capabilities`() {
        val draft = ProfileManagement.draft("new")
        assertFalse(draft.enabled)
        assertEquals(setOf(ModelCapability.CHAT), draft.capabilities)
        assertTrue(draft.apiKeyRef != first.apiKeyRef)
        assertEquals("wire", ProfileManagement.draft("copy", first).modelId)
        assertEquals(first.connectionId, ProfileManagement.draft("copy", first).connectionId)
    }

    @Test fun `shared edit is explicit atomic and retains model fields`() {
        val second = first.copy(id = "two", modelId = "other-wire")
        val current = listOf(first, second)
        val changed = first.copy(baseUrl = "https://changed.test/v1")
        assertTrue(runCatching { ProfileManagement.apply(current, ProfileEdit.Save(changed)) }.isFailure)
        val result = ProfileManagement.apply(current, ProfileEdit.Save(changed, true))
        assertEquals(changed.baseUrl, result.last().baseUrl)
        assertEquals(second.modelId, result.last().modelId)
        assertEquals(first.baseUrl, current.first().baseUrl)
        assertEquals(result, ProfileSnapshot.decode(ProfileSnapshot.encode(result)))
    }

    @Test fun `independent copy and deletion do not modify shared references or credentials`() {
        val copy = ProfileManagement.draft("copy", first).copy(connectionId = "independent", apiKeyRef = "new-ref")
        val added = ProfileManagement.apply(listOf(first), ProfileEdit.Save(copy))
        assertEquals(first, added.first())
        val deleted = ProfileManagement.apply(added, ProfileEdit.Remove(copy.id))
        assertEquals(listOf(first), deleted)
        assertTrue(runCatching {
            ProfileManagement.apply(listOf(first.copy(enabled = true)), ProfileEdit.Remove(first.id))
        }.isFailure)
    }

    @Test fun `failed managed write preserves the original snapshot`() {
        val owner = ProfilePersistence(ProfileSnapshot.encode(listOf(first)), null) { false }
        val next = ProfileManagement.apply(owner.profiles, ProfileEdit.Save(first.copy(modelId = "new-wire")))
        assertTrue(runCatching { owner.save(next) }.isFailure)
        assertEquals(first, owner.profiles.single())
    }
}
