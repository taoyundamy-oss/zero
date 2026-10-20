package com.zero.v2.services.chatgpt

import java.io.IOException
import org.json.JSONObject

/** OAuth errors are machine-classified; provider descriptions may echo secrets and never escape. */
internal class ChatGptTokenFailure(status: Int, body: JSONObject?) : IOException("ChatGPT授权HTTP $status") {
    val terminal = code(body) in setOf("invalid_grant", "invalid_refresh_token", "token_expired",
        "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
    private fun code(body: JSONObject?): String = body?.optJSONObject("error")?.optString("code")
        ?: body?.optString("error").orEmpty()
}
