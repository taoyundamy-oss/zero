package com.zero.v2.services.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlamaMessageProjectionTest {
    @Test fun `projection preserves stable persona and append only historical snapshots`() {
        val messages = JSONArray("""[{"role":"system","content":"persona"},
            {"role":"system","content":"context1"},{"role":"user","content":"hello"}]""")
        val body = JSONObject().put("messages", messages)
        LocalLlamaMessageProjection.apply(body)
        val first = body.getJSONArray("messages")
        assertEquals("system", first.getJSONObject(0).getString("role"))
        assertEquals("persona", first.getJSONObject(0).getString("content"))
        assertEquals("user", first.getJSONObject(1).getString("role"))
        assertTrue(first.getJSONObject(1).getString("content").endsWith("context1"))
        assertEquals("system", messages.getJSONObject(1).getString("role"))
        val next = JSONObject().put("messages", JSONArray(messages.toString())
            .put(JSONObject("""{"role":"assistant","content":"hello"}"""))
            .put(JSONObject("""{"role":"system","content":"context2"}"""))
            .put(JSONObject("""{"role":"user","content":"continue"}""")))
        LocalLlamaMessageProjection.apply(next)
        for (index in 0 until first.length()) {
            val projected = next.getJSONArray("messages").getJSONObject(index)
            assertEquals(first.getJSONObject(index).toString(), projected.toString())
        }
        val once = next.toString()
        LocalLlamaMessageProjection.apply(next)
        assertEquals(once, next.toString())
    }
}
