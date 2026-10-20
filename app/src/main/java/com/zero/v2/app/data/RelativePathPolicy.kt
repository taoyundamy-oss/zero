package com.zero.v2.app.data

/** SAF 相对路径的分段与穿越防护策略。 */
internal object RelativePathPolicy {
    fun segments(path: String, allowEmpty: Boolean = false): List<String>? {
        val parts = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return if (allowEmpty) emptyList() else null
        if (parts.any { it == "." || it == ".." }) return null
        return parts
    }
}
