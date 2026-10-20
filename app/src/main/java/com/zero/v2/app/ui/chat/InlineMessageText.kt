package com.zero.v2.app.ui.chat

/** Display-only projection. Never rewrites stored conversation or provider input. */
internal object InlineMessageText {
    data class Part(val text: String, val url: String? = null, val code: Boolean = false, val bold: Boolean = false)
    private val start = Regex("https?://|amapuri://|geo:|tel:|mailto:", RegexOption.IGNORE_CASE)

    fun parse(source: String): List<Part> {
        val parts = mutableListOf<Part>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { parts += Part(plain.toString()); plain.setLength(0) } }
        fun emit(part: Part) { flush(); parts += part }
        var index = 0
        while (index < source.length) {

            if (source[index] == '\\' && index + 1 < source.length && source[index + 1] in "[]()`*\\") {
                plain.append(source[index + 1]); index += 2; continue
            }
            if (source[index] == '`') {
                val fence = source.substring(index).takeWhile { it == '`' }
                val end = source.indexOf(fence, index + fence.length)
                if (end >= 0) {
                    emit(Part(source.substring(index + fence.length, end), code = true))
                    index = end + fence.length; continue
                }
                // An unfinished streaming code span is not a clickable URL.
                emit(Part(source.substring(index), code = true)); break
            }
            if (source.startsWith("**", index)) {
                val end = source.indexOf("**", index + 2)
                if (end > index + 2) {
                    flush(); parts += parse(source.substring(index + 2, end)).map { it.copy(bold = true) }
                    index = end + 2; continue
                }
            }
            if (source[index] == '[') {
                val labelEnd = source.indexOf("](", index + 1)
                if (labelEnd >= 0) {
                    val end = targetEnd(source, labelEnd + 2)
                    if (end >= 0) {
                        val url = source.substring(labelEnd + 2, end).trim().removeSurrounding("<", ">")
                        emit(Part(source.substring(index + 1, labelEnd), url.takeIf(::allowed)))
                        index = end + 1; continue
                    }
                }
            }
            val match = start.matchAt(source, index)
            if (match != null) {
                var end = index + match.value.length
                var parentheses = 0
                while (end < source.length) {
                    val c = source[end]
                    if (c.isWhitespace() || c in "<>\"'`[]，。；！？、（）") break
                    if (c == '(') parentheses++
                    if (c == ')') { if (parentheses == 0) break else parentheses-- }
                    end++
                }
                val url = source.substring(index, end).trimEnd('.', ',', ';', '!', '?', ':')
                if (allowed(url)) {
                    emit(Part(url, url)); index += url.length; continue
                }
            }
            plain.append(source[index])
            index++
        }
        flush()
        return parts
    }

    private fun targetEnd(source: String, from: Int): Int {
        var depth = 0
        for (i in from until source.length) {
            if (source[i] == '\n') return -1
            if (source[i] == '(') depth++
            if (source[i] == ')') { if (depth == 0) return i else depth-- }
        }
        return -1
    }

    private fun allowed(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        when (uri.scheme?.lowercase()) {
            "http", "https" -> !uri.rawAuthority.isNullOrBlank() && uri.rawUserInfo == null
            "amapuri", "geo", "tel", "mailto" -> !uri.rawSchemeSpecificPart.isNullOrBlank()
            else -> false
        }
    }.getOrDefault(false)
}
