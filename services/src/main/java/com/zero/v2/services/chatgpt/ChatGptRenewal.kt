package com.zero.v2.services.chatgpt

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class ChatGptRenewal(private val sessions: ChatGptSessions,
    private val exchange: suspend (JSONObject) -> Pair<JSONObject, JSONObject>,
    private val now: () -> Long) {
    private val mutex = Mutex()
    suspend fun token(client: String): String = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val ticket = sessions.credentials(client)
        val account = requireNotNull(ticket.account)
        check(allowed(account)) { "ChatGPT plan permission not granted" }
        if (account.getLong("expires_at") > now() + REFRESH_MARGIN_SECONDS) {
            return@withLock account.getString("access_token")
        }
        check(account.optString("refresh_token").isNotBlank()) { "ChatGPT sign-in required" }
        check(now() >= account.optLong("earliest_refresh_at", 0)) { "ChatGPT refresh not yet available" }
        val (tokens, claims) = try { exchange(account) } catch (failure: ChatGptTokenFailure) {
            if (failure.terminal) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                sessions.clear(ticket)
            }
            throw failure
        }
        currentCoroutineContext().ensureActive()
        require(tokens.getString("refresh_token").isNotBlank())
        sessions.save(ticket, client, tokens, claims, now())
        val saved = requireNotNull(sessions.credentials(client).account)
        check(allowed(saved)) { "ChatGPT plan permission no longer granted" }
        saved.getString("access_token")
    }
    private fun allowed(a: JSONObject) = ChatGptOAuth.PLAN_SCOPE in a.optString("scope").split(' ')
    companion object { private const val REFRESH_MARGIN_SECONDS = 60 }
}
