package com.zero.v2.services.termux

import java.net.URLEncoder

/** Termux 工作站命令的纯参数策略，避免通信编排类同时承担输入净化职责。 */
internal object TermuxCommandPolicy {
    fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** SAF 落点名净化：只保留相对文件名，阻断路径穿越与 provider 特殊字符。 */
    fun sanitizeFileName(raw: String): String {
        var name =
            raw.substringAfterLast('/').substringAfterLast('\\').trim()
                .replace(Regex("[/\\\\:*?\"<>|%#]"), "_")
        if (name.isBlank() || name == "." || name == "..") name = "linux_${System.currentTimeMillis()}"
        return name.take(120)
    }
}
