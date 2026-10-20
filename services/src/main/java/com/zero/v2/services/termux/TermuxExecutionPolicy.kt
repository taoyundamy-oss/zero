package com.zero.v2.services.termux

/** 本地 Termux 执行边界：统一 LLM 参数裁剪与 supervisor 请求超时余量。 */
internal object TermuxExecutionPolicy {
    const val DEFAULT_EXEC_TIMEOUT_S = 120
    const val HTTP_GRACE_S = 15

    fun resolveTimeoutSeconds(
        requested: Int?,
        maxAllowed: Int,
    ): Int = (requested ?: DEFAULT_EXEC_TIMEOUT_S).coerceIn(1, maxAllowed)

    fun requestTimeoutMs(execTimeoutSeconds: Int): Long = (execTimeoutSeconds + HTTP_GRACE_S) * 1000L
}
