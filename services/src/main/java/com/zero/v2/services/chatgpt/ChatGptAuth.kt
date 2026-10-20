package com.zero.v2.services.chatgpt

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ChatGptAccount(val clientId: String, val label: String, val signedIn: Boolean, val planAllowed: Boolean)
data class ChatGptAuthState(val accounts: List<ChatGptAccount> = emptyList(), val active: String? = null,
    val busy: Boolean = false, val message: String = "尚未登录；登录后可添加套餐模型，现有模型保持不变。",
    val canCancel: Boolean = false)

@Singleton
class ChatGptAuth @Inject constructor(vault: ChatGptVault) {
    private val sessions = ChatGptSessions(vault)
    private val http = ChatGptHttp()
    private val operation = Mutex()
    private val renewal = ChatGptRenewal(sessions, { account ->
        val tokens = http.refresh(account)
        val claims = if (tokens.optString("id_token").isNotBlank()) {
            ChatGptOAuth.identity(tokens.getString("id_token"), http.jwks(),
                account.getString("client_id"), null, now())
        } else JSONObject().put("sub", account.getString("subject")).put("email", account.optString("email"))
        tokens to claims
    }, ::now)
    private val mutable = MutableStateFlow(ChatGptAuthState())
    val state = mutable.asStateFlow()
    private val revision = MutableStateFlow(0L)
    fun ready(client: String): Boolean = state.value.let { current ->
        !current.busy && current.active == client && current.accounts.any {
            it.clientId == client && it.signedIn && it.planAllowed
        }
    }
    fun lease(client: String): ChatGptLease = ChatGptLease(revision) {
        check(ready(client)) { "ChatGPT账号未登录或未获套餐授权" }
        accessToken(client)
    }

    suspend fun reload() = withContext(Dispatchers.IO) {
        operation.withLock { report("已读取本机账号；未完成的登录需重试。") }
    }
    private suspend fun report(message: String, busy: Boolean = false, canCancel: Boolean = false) {
        runCatching {
            val data = sessions.view()
            val list = data.getJSONArray("accounts")
            mutable.value = ChatGptAuthState((0 until list.length()).map { index ->
                val a = list.getJSONObject(index)
                ChatGptAccount(a.getString("client_id"), a.optString("email").ifBlank { "ChatGPT账号" } +
                    " · " + a.getString("client_id").takeLast(LABEL_SUFFIX),
                    a.optString("access_token").isNotBlank(), allowed(a))
            }, data.optString("active").ifBlank { null }, busy, message, canCancel)
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            mutable.value = ChatGptAuthState(message = "ChatGPT安全存储不可用；原数据保留，请解锁后重试。")
        }
    }

    suspend fun signIn(client: String?, openBrowser: (String) -> Unit) = withContext(Dispatchers.IO) {
        operation.withLock {
            runCatching {
                revision.update { it + 1 }
                val ticket = sessions.begin(client)
                ChatGptCallback().use { listener ->
                    val old = ticket.account
                    val attempt = ChatGptOAuth.Attempt(ticket.host, listener.redirect,
                        old?.getString("client_id") ?: ChatGptOAuth.DYNAMIC,
                        old?.optString("id_token")?.takeIf { it.isNotBlank() }, old?.getString("subject"))
                    report("请在系统浏览器完成登录，然后返回Zero。", busy = true, canCancel = true)
                    withContext(Dispatchers.Main) { openBrowser(attempt.url()) }
                    val callback = listener.await(attempt)
                    check(!callback.denied) { "Authorization declined" }
                    val token = http.exchange(attempt, callback)
                    val identity = ChatGptOAuth.identity(token.getString("id_token"), http.jwks(),
                        callback.client, attempt.nonce, now())
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    sessions.save(ticket, callback.client, token, identity, now())
                    revision.update { it + 1 }
                    report("账号已保存；可读取模型目录并主动添加，现有模型保持不变。")
                }
            }.onFailure { failure ->
                if (failure is CancellationException) {
                    withContext(NonCancellable) { sessions.invalidate(); report("登录已取消；原账号保留。") }
                    throw failure
                }
                sessions.invalidate()
                report("登录未完成：可能已拒绝、超时、网络或身份校验失败；请重试，原账号保留。")
            }
        }
    }

    suspend fun select(client: String) = withContext(Dispatchers.IO) {
        operation.withLock {
            runCatching {
                revision.update { it + 1 }
                sessions.select(client)
                report("已选择ChatGPT账号；现有模型路由未改变。")
            }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    report("账号切换未保存；请重试。")
                }
        }
    }

    suspend fun signOut(client: String) = withContext(NonCancellable + Dispatchers.IO) {
        operation.withLock {
            runCatching {
                revision.update { it + 1 }
                val ticket = sessions.startSignOut(client)
                report("正在退出…", true)
                val revoked = runCatching { http.revoke(requireNotNull(ticket.account)) }.getOrDefault(false)
                sessions.clear(ticket)
                report(if (revoked) "已退出；保留账号注册信息供重新登录。" else
                    "已退出本地账号；远端撤销未确认，可在ChatGPT设置中断开Zero。")
            }.onFailure {
                report("退出未完成，安全存储操作失败；请重试。")
            }
        }
    }

    /** Renew on demand. Merely opening settings never starts a cloud request. */
    internal suspend fun accessToken(client: String): String = withContext(Dispatchers.IO) {
        try { renewal.token(client) } catch (failure: ChatGptTokenFailure) {
            if (failure.terminal) {
                revision.update { it + 1 }
                report("登录凭据已失效；请使用保留的账号重新登录。")
            }
            throw failure
        }
    }
    private fun allowed(a: JSONObject) = ChatGptOAuth.PLAN_SCOPE in a.optString("scope").split(' ')
    private fun now() = System.currentTimeMillis() / MILLIS_PER_SECOND
    companion object {
        private const val LABEL_SUFFIX = 8
        private const val MILLIS_PER_SECOND = 1000
    }
}

