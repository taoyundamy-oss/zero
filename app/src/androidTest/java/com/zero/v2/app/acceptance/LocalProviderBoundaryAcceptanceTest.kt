package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Real local preflight; no profile writes and no generation for oversized input. */
class LocalProviderBoundaryAcceptanceTest {
    @Test fun oversizedPromptIsRejectedBeforeGeneration() = runBlocking<Unit> {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is ProviderAcceptanceRunner)
        check(InstrumentationRegistry.getArguments().getString("localBoundary") == "true")
        val context = runner.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val profile = requireNotNull(entry.profiles().get("local-qwen"))
        check(profile.localLlamaPreflight && profile.baseUrl == "http://127.0.0.1:8080/v1")
        val client = entry.llmClient()
        val key = client.resolveApiKey(profile)
        val identity = client.requestHealth.identity(profile, key, "chat_stream")
        check(client.requestHealth.observation(identity) == null)
        val report = JSONObject()
        try {
            client.chatStream(profile, listOf(ChatMessage(role = MessageRole.USER,
                content = "超长输入".repeat(15_000))), "本地边界验收", emptyList()).collect()
            error("oversized_prompt_was_not_rejected")
        } catch (failure: java.io.IOException) {
            report.put("error", failure.message)
            check(failure.message.orEmpty().startsWith("LOCAL_CONTEXT_LIMIT"))
            check(client.requestHealth.observation(identity) == null)
            report.put("generationHttpNotSent", true).put("passed", true)
        } finally {
            File(context.filesDir, "llm-u6b-local-boundary.json").writeText(report.toString(2))
        }
    }
}
