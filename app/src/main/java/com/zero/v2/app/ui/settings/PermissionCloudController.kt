package com.zero.v2.app.ui.settings

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

data class ImConfigState(
    val loaded: Boolean = false,
    val whitelist: String = "",
    val dailyCap: Int = 200,
    val capText: String = "200",
    val autoForward: Boolean = true,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

internal data class PermissionCloudState(
    val config: ImConfigState,
    val loggedIn: Boolean? = null,
    val statusError: String? = null,
    val loginUrl: String = "",
    val loginBusy: Boolean = false,
    val loginMessage: String? = null,
)

/** Main-thread owner for remote receipts and saved drafts; no timer outside an explicit login. */
internal class PermissionCloudController(
    private val scope: CoroutineScope,
    private val saved: SavedStateHandle,
    private val cloud: PermissionCloudCall,
) {
    private val mutable = MutableStateFlow(PermissionCloudState(ImConfigState(
        whitelist = saved["im.whitelist"] ?: "", capText = saved["im.cap"] ?: "200",
        dailyCap = (saved.get<String>("im.cap") ?: "200").toIntOrNull() ?: 200,
        autoForward = saved["im.auto"] ?: true, dirty = saved["im.dirty"] ?: false,
    )))
    val state = mutable.asStateFlow()
    private var revision = 0L
    private var loadGeneration = 0L
    private var loginGeneration = 0L
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var loginJob: Job? = null
    private var statusJob: Job? = null

    private fun config(update: (ImConfigState) -> ImConfigState) {
        mutable.update { it.copy(config = update(it.config)) }
    }

    fun edit(whitelist: String, cap: String, autoForward: Boolean) {
        revision++
        saved["im.whitelist"] = whitelist
        saved["im.cap"] = cap
        saved["im.auto"] = autoForward
        saved["im.dirty"] = true
        config { it.copy(whitelist = whitelist, capText = cap, dailyCap = cap.toIntOrNull() ?: it.dailyCap,
            autoForward = autoForward, dirty = true, error = null, message = null) }
    }

    private suspend fun read(tool: String, args: Map<String, Any?> = emptyMap()): JSONObject? = try {
        cloud.call(tool, args).also { currentCoroutineContext().ensureActive() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    fun load() {
        if (state.value.config.saving) return
        val generation = ++loadGeneration
        loadJob?.cancel()
        config { it.copy(loading = true, error = null, message = null) }
        loadJob = scope.launch {
            try {
                val remote = decode(read("im_config_get"))
                if (generation == loadGeneration) config { current ->
                    when {
                        remote == null -> current.copy(error = "无法读取通道参数，请重试。")
                        current.dirty -> current.copy(loaded = true, message = "已读取，保留尚未保存的编辑。")
                        else -> remote
                    }
                }
            } finally {
                if (generation == loadGeneration) config { it.copy(loading = false) }
            }
        }
    }

    fun save() {
        val request = state.value.config
        if (!request.loaded || request.loading || request.saving) return
        val cap = request.capText.toIntOrNull()
        if (cap == null || cap < 0) {
            config { it.copy(error = "请输入有效的非负整数上限。", message = null) }
            return
        }
        val version = revision
        val whitelist = request.whitelist.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
        config { it.copy(saving = true, error = null, message = null) }
        saveJob = scope.launch {
            try {
                val receipt = decode(read("im_config_set", mapOf("updates" to mapOf(
                    "forward_whitelist" to whitelist, "daily_forward_cap" to cap,
                    "auto_forward_enabled" to request.autoForward))))
                val confirmed = receipt != null && receipt.whitelist == whitelist.joinToString(",") &&
                    receipt.dailyCap == cap && receipt.autoForward == request.autoForward
                config { current ->
                    if (!confirmed) current.copy(error = "未能确认保存结果，请重新读取核对。")
                    else {
                        val edited = revision != version
                        if (!edited) saved["im.dirty"] = false
                        current.copy(dirty = edited, message = if (edited)
                            "已保存提交的参数；后续编辑尚未保存。" else "参数已保存。")
                    }
                }
            } finally {
                config { it.copy(saving = false) }
            }
        }
    }

    fun cancelConfig() {
        val wasSaving = state.value.config.saving
        loadGeneration++
        loadJob?.cancel()
        saveJob?.cancel()
        config { it.copy(loading = false, message = if (wasSaving)
            "已停止等待；远端可能已保存，请重新读取核对。" else "已取消读取。") }
        // Saving stays busy until cancellation completes; a remote commit cannot be undone locally.
    }

    fun probe() {
        if (statusJob?.isActive == true || state.value.loginBusy) return
        statusJob = scope.launch {
            val logged = decodeStatus(read("im_status"))
            mutable.update { it.copy(loggedIn = logged,
                statusError = if (logged == null) "暂时无法获取通道状态，请刷新重试。" else null) }
        }
    }

    fun startLogin() {
        if (state.value.loginBusy) return
        statusJob?.cancel()
        val generation = ++loginGeneration
        mutable.update { it.copy(loginBusy = true, loginUrl = "", loginMessage = null) }
        loginJob = scope.launch {
            try {
                val url = read("im_login", mapOf("channel" to "WECHAT_PRIVATE"))?.optString("qr_url").orEmpty()
                val uri = android.net.Uri.parse(url)
                if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank()) {
                    mutable.update { it.copy(loginMessage = "未能获取登录链接，请重试。") }
                } else {
                    mutable.update { it.copy(loginUrl = url) }
                    val confirmed = withTimeoutOrNull(120_000) {
                        var connected = false
                        while (!connected) {
                            delay(5_000)
                            connected = decodeStatus(read("im_status")) == true
                        }
                        true
                    } == true
                    mutable.update { it.copy(loggedIn = if (confirmed) true else it.loggedIn,
                        loginUrl = if (confirmed) "" else it.loginUrl,
                        loginMessage = if (confirmed) "微信通道已连接。" else "等待确认已超时，可重试登录。") }
                }
            } finally {
                if (generation == loginGeneration) mutable.update { it.copy(loginBusy = false) }
            }
        }
    }

    fun cancelLogin() {
        loginGeneration++
        loginJob?.cancel()
        mutable.update { it.copy(loginBusy = false, loginUrl = "", loginMessage = "已停止等待登录确认。") }
    }

    private fun decodeStatus(result: JSONObject?): Boolean? = result?.takeIf {
        it.opt("ok") == true && it.opt("channels_text") is String
    }?.let { Regex("openclaw-weixin[^\\n]*\\brunning\\b").containsMatchIn(it.getString("channels_text")) }

    private fun decode(result: JSONObject?): ImConfigState? {
        val array = result?.optJSONArray("forward_whitelist")
        val cap = result?.opt("daily_forward_cap") as? Number
        val auto = result?.opt("auto_forward_enabled") as? Boolean
        val valid = array != null && cap != null && auto != null && cap.toDouble() >= 0 &&
            cap.toDouble() <= Int.MAX_VALUE && cap.toDouble() == cap.toInt().toDouble()
        return if (!valid || (0 until array!!.length()).any { array.opt(it) !is String }) null
        else ImConfigState(loaded = true, whitelist = (0 until array.length()).joinToString(",") { array.getString(it) },
            dailyCap = cap!!.toInt(), capText = cap.toInt().toString(), autoForward = auto!!)
    }
}
