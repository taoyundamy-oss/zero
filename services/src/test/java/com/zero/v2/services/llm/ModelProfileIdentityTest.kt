package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.InputModality
import com.zero.v2.core.common.ModelRequirements
import com.zero.v2.core.common.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelProfileIdentityTest {
    private class Model(
        override val profileId: String,
        override val priority: Int = 0,
        override val modelId: String = "same-model",
    ) : ILanguageModel {
        override val displayName = profileId
        override val contextWindow = 8192
        override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
        override val keyConfigured = true
        override val enabled = true
        override fun chatStream(
            messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>,
            prefixVersion: String?,
        ): Flow<StreamChunk> = emptyFlow()
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = "unused"
    }

    @Test fun `same wire model registers independently and routes by configured priority`() {
        val selector = ModelSelector()
        val local = Model("local", 20)
        val gateway = Model("gateway", 10)
        selector.register(local)
        selector.register(gateway)
        selector.register(local)
        assertEquals(2, selector.getAll().size)
        assertEquals(local, selector.selectModel(ModelRequirements(setOf(InputModality.TEXT), needsTools = true)))
        assertNull(selector.getById("same-model"))
        assertEquals(gateway, selector.getByProfileId("gateway"))
        selector.unregister("local")
        assertEquals(listOf(gateway), selector.getAll())
        assertEquals(gateway, selector.selectModel(ModelRequirements(setOf(InputModality.TEXT), needsTools = true)))
    }

    @Test fun `refresh model id removes old configuration without unregistering another connection`() {
        val selector = ModelSelector()
        val direct = Model("direct")
        selector.register(direct)
        selector.register(Model("gateway"))
        selector.unregister("gateway")
        selector.register(Model("gateway", modelId = "new-model"))
        assertEquals(direct, selector.getById("same-model"))
        assertEquals("new-model", selector.getByProfileId("gateway")?.modelId)
    }

    @Test fun `same model priority ties are independent of registration order`() {
        val first = Model("a")
        val second = Model("b")
        val forward = ModelSelector().apply { register(first); register(second) }
        val backward = ModelSelector().apply { register(second); register(first) }
        val requirements = ModelRequirements(setOf(InputModality.TEXT))
        assertEquals(forward.selectModel(requirements), backward.selectModel(requirements))
    }
    @Test fun `refresh publishes complete list and retains registrations from other owners`() {
        val selector = ModelSelector()
        val external = Model("external")
        selector.register(external)
        selector.replaceProfiles(emptySet(), listOf(Model("direct"), Model("gateway")))
        selector.replaceProfiles(setOf("direct", "gateway"), listOf(Model("gateway", modelId = "updated")))
        assertEquals(setOf("external", "gateway"), selector.getAll().map { it.profileId }.toSet())
        assertEquals("updated", selector.getByProfileId("gateway")?.modelId)
        selector.replaceProfiles(setOf("gateway"), emptyList())
        assertEquals(listOf(external), selector.getAll())
    }

    @Test fun `rejected refresh preserves previously registered models`() {
        val selector = ModelSelector()
        val external = Model("external")
        selector.register(external)
        val duplicate = runCatching {
            selector.replaceProfiles(emptySet(), listOf(Model("a"), Model("a")))
        }
        assertEquals(true, duplicate.isFailure)
        val collision = runCatching { selector.replaceProfiles(emptySet(), listOf(Model("external"))) }
        assertEquals(true, collision.isFailure)
        assertEquals(listOf(external), selector.getAll())
    }
}
