package com.zero.v2.services.llm

import org.json.JSONObject
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheRequestDiagnosticsTest {
    @Test fun `restored diagnostic baseline distinguishes process restart from cold provider cache`() {
        val first = CacheRequestDiagnostics()
        val body = JSONObject("""{"messages":[{"content":"private-content"}]}""")
        first.describe("private-scope", body)
        val saved = first.snapshot()
        assertTrue(!saved.contains("private"))
        val restored = CacheRequestDiagnostics()
        restored.restore(saved)
        val report = restored.describe("private-scope", body)
        assertTrue(report.contains("reason=append_only"))
        assertTrue(report.contains("baseline=restored"))
        body.getJSONArray("messages").getJSONObject(0).put("content", "changed")
        assertTrue(restored.describe("private-scope", body).contains("diffIndex=0 diffFields=content"))
    }

    @Test fun `restored header change and different account are not reported as append only`() {
        val first = CacheRequestDiagnostics()
        val body = JSONObject("""{"model":"one","messages":[]}""")
        first.describe("account-one", body)
        val restored = CacheRequestDiagnostics()
        restored.restore(first.snapshot())
        body.put("model", "two")
        assertTrue(restored.describe("account-one", body).contains("diffFields=model"))
        assertTrue(restored.describe("account-two", body).contains("reason=first_request"))
        assertTrue(runCatching { restored.restore("{\"version\":2,\"rows\":[]}") }.isFailure)
    }
    @Test fun `logical edit is tied to hashed message identity and source`() {
        val tracker = CacheRequestDiagnostics()
        val first = ChatMessage(id = "private-message-id", role = MessageRole.USER, content = "private-before")
        val body = JSONObject("""{"messages":[{"role":"user","content":"private-before"}]}""")
        tracker.describe("conversation", body, listOf(first))
        body.getJSONArray("messages").getJSONObject(0).put("content", "private-after")
        val report = tracker.describe("conversation", body, listOf(first.copy(content = "private-after")))
        assertTrue(report.contains("logicalIndex=0"))
        assertTrue(report.contains("logicalFields=content"))
        assertTrue(report.contains("source=USER:CHAT_WINDOW"))
        assertTrue(!report.contains("private"))
    }

    @Test fun `unknown field names are redacted and history removal is explicit`() {
        val tracker = CacheRequestDiagnostics()
        val body = JSONObject("""{"messages":[{"content":"one"},{"content":"two"}]}""")
        tracker.describe("scope", body)
        body.getJSONArray("messages").remove(1)
        assertTrue(tracker.describe("scope", body).contains("diffIndex=1 diffFields=length"))
        body.put("secret-header-name", "secret-value")
        val report = tracker.describe("scope", body)
        assertTrue(report.contains("diffIndex=header diffFields=other"))
        assertTrue(!report.contains("secret"))
    }
    @Test fun `history diagnostics locate changed wire field without revealing its value`() {
        val tracker = CacheRequestDiagnostics()
        val body = JSONObject("""{"input":[{"type":"function_call_output",
            "call_id":"private-id","output":"secret-before"}]}""")
        tracker.describe("scope", body)
        body.getJSONArray("input").getJSONObject(0).put("output", "secret-after")
        val report = tracker.describe("scope", body)
        assertTrue(report.contains("diffIndex=0"))
        assertTrue(report.contains("diffFields=output"))
        assertTrue(!report.contains("secret") && !report.contains("private-id"))
    }
    @Test fun `actual request fields distinguish schema churn from historical rewrite`() {
        val tracker = CacheRequestDiagnostics()
        val first = JSONObject(
            """{"model":"test","tools":[{"name":"discover"}],"messages":[{"role":"user","content":"private"}]}""",
        )
        assertTrue(tracker.describe("conversation", first).contains("first_request"))
        val next = JSONObject(first.toString())
        next.getJSONArray("messages").put(JSONObject("""{"role":"assistant","content":"answer"}"""))
        val result = tracker.describe("conversation", next)
        assertTrue(result.contains("append_only"))
        assertTrue(!result.contains("private"))
        next.getJSONArray("messages").getJSONObject(0).put("content", "changed")
        assertTrue(tracker.describe("conversation", next).contains("history_changed"))
        next.getJSONArray("tools").getJSONObject(0).put("name", "different")
        assertTrue(tracker.describe("conversation", next).contains("tools_or_settings_changed"))
    }
}
