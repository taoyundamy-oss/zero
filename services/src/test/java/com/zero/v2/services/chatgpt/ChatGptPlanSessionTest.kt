package com.zero.v2.services.chatgpt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.zero.v2.services.llm.ModelCapability

class ChatGptPlanSessionTest {
    @Test fun leaseRejectsLateTokenAndSwitchBackButAllowsRotation() = runBlocking<Unit> {
        val revision = MutableStateFlow(0L)
        var token = "first"
        val lease = ChatGptLease(revision) { token }
        assertEquals("first", lease.token())
        token = "second"
        assertEquals("second", lease.token())
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val pending = ChatGptLease(revision) { entered.complete(Unit); finish.await(); token }
        val job = async { runCatching { pending.token() } }
        entered.await()
        revision.value += 2
        finish.complete(Unit)
        assertTrue(job.await().isFailure)
        assertTrue(runCatching { lease.checkCurrent() }.isFailure)
        lease.awaitInvalidation()
    }

    @Test fun catalogUsesServerOrderStableAccountIdsAndOnlyDeclaredCapabilities() {
        val json = JSONObject("""{"models":[
            {"slug":"z","display_name":"First","visibility":"list","context_window":16384,
             "supports_tool_calls":true,"input_modalities":["text","image"]},
            {"slug":"hidden","display_name":"Hidden","visibility":"hidden"},
            {"slug":"a","display_name":"Second","visibility":"list"}]}""")
        val first = ChatGptCatalog.parse("oaiapp_one", json)
        assertEquals(listOf("z", "a"), first.map { it.modelId })
        assertEquals(16384, first[0].contextWindow)
        assertTrue(ModelCapability.IMAGE_INPUT in first[0].capabilities)
        assertEquals(setOf(ModelCapability.CHAT), first[1].capabilities)
        assertTrue(first.all { !it.enabled && it.apiKeyRef.isEmpty() && it.tokenPrices.subscription })
        assertEquals(first.map { it.id }, ChatGptCatalog.parse("oaiapp_one", json).map { it.id })
        assertFalse(first[0].id == ChatGptCatalog.parse("oaiapp_two", json)[0].id)
    }

    @Test fun transportNeverRetriesOrRedirectsAndErrorsCannotLeakServerEcho() {
        assertFalse(ChatGptInferenceHttp.client.followRedirects)
        assertFalse(ChatGptInferenceHttp.client.followSslRedirects)
        assertFalse(ChatGptInferenceHttp.client.retryOnConnectionFailure)
        val response = okhttp3.Response.Builder().request(okhttp3.Request.Builder()
            .url("https://api.openai.com/v1/responses").build()).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(403).message("Forbidden").body(okhttp3.ResponseBody.create(null,
                """{"error":{"code":"echo-secret","message":"secret-body"}}""")).build()
        response.use {
            val error = ChatGptInferenceHttp.failure(it).message.orEmpty()
            assertTrue(error.contains("403"))
            assertFalse(error.contains("secret"))
        }
    }
}
