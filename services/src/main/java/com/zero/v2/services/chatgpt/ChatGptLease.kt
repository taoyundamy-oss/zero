package com.zero.v2.services.chatgpt

import java.io.IOException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Captures account-session identity, never a bearer value. Refresh does not invalidate this lease. */
class ChatGptLease internal constructor(
    private val revision: StateFlow<Long>,
    private val readToken: suspend () -> String,
) {
    private val captured = revision.value
    fun checkCurrent() {
        if (revision.value != captured) throw IOException("ChatGPT账号已变更，请开始新回合")
    }
    suspend fun token(): String {
        checkCurrent()
        return readToken().also { checkCurrent() }
    }
    suspend fun awaitInvalidation() { revision.first { it != captured } }
}
