package com.zero.v2.services.security

import android.content.SharedPreferences

/** A failed encrypted open/read/write never selects a plaintext writer. Retry can recover. */
internal class CredentialStorageGate(private val openEncrypted: () -> SharedPreferences) {
    private var cached: SharedPreferences? = null
    var error: String? = null
        private set

    @Synchronized fun <T> access(action: (SharedPreferences) -> T): T = try {
        val store = cached ?: openEncrypted().also { cached = it }
        action(store).also { error = null }
    } catch (_: Exception) {
        cached = null
        error = "安全凭据存储不可用，已禁止明文降级；请解锁设备后重试，旧数据保留。"
        throw IllegalStateException(error)
    }

    @Synchronized fun invalidate() { cached = null }
}

/** Copy/verify/activate only. The legacy source is never edited or deleted here. */
internal fun migrateLegacyCredentials(
    source: Map<String, *>, encrypted: SharedPreferences, activate: () -> Boolean,
) {
    require(source.values.all { it is String }) { "旧凭据格式异常，原数据保留" }
    val editor = encrypted.edit()
    source.forEach { (key, value) -> editor.putString(key, value as String) }
    check(editor.commit()) { "加密副本保存失败，原数据保留" }
    check(source.all { (key, value) -> encrypted.getString(key, null) == value }) { "加密副本校验失败，原数据保留" }
    check(activate()) { "加密存储切换失败，原数据保留" }
}
