package com.zero.v2.core.common

/**
 * 附件元信息 JSON 编解码（M1）——持久化附件引用到 messages.attachments 列。
 *
 * **只编 uri/mimeType/type，刻意不编 base64Data**（运行时按 uri 引用读取，不入库/日志/Trace）。
 * 受控格式：`[{"uri":"...","mimeType":"...","type":"IMAGE"},...]`；decode 用正则取串值（容错，
 * 损坏/非预期格式→空列表，不抛）。纯逻辑无 org.json 依赖，JVM 可测。
 */
object AttachmentCodec {

    fun encode(attachments: List<Attachment>): String {
        if (attachments.isEmpty()) return "[]"
        return attachments.joinToString(",", "[", "]", transform = { a ->
            val obj = StringBuilder("{")
            obj.append("\"uri\":").append(q(a.uri))
            obj.append(",\"mimeType\":").append(q(a.mimeType))
            obj.append(",\"type\":").append(q(a.type.name))
            obj.append(",\"route\":").append(q(a.route.name))
            obj.append("}")
            obj.toString()
        })
    }

    fun decode(json: String?): List<Attachment> {
        if (json.isNullOrBlank()) return emptyList()
        val s = json.trim()
        if (!s.startsWith("[") || !s.endsWith("]")) return emptyList()
        val inner = s.substring(1, s.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        // 按花括号深度拆出对象串
        val objs = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in inner.indices) {
            when (inner[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { objs.add(inner.substring(start, i + 1)); start = i + 1 }
                }
            }
        }
        return objs.mapNotNull { parseObject(it) }
    }

    private fun parseObject(obj: String): Attachment? {
        val fields = parseStringFields(obj)
        val uri = fields["uri"] ?: return null
        val mime = fields["mimeType"] ?: "application/octet-stream"
        val type = fields["type"]?.let { runCatching { AttachmentType.valueOf(it) }.getOrNull() }
            ?: AttachmentType.IMAGE
        // base64Data 刻意不恢复——按引用读取，不入库
        val route = fields["route"]?.let { runCatching { MediaRoute.valueOf(it) }.getOrNull() } ?: MediaRoute.AUTO
        return Attachment(uri = uri, mimeType = mime, type = type, route = route)
    }

    /** 正则提取 "key":"value"（值含转义），unquote 还原。容错：损坏→空 map。 */
    private fun parseStringFields(obj: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pattern = Regex(""""(\w+)":"((?:[^"\\]|\\.)*)"""")
        for (m in pattern.findAll(obj)) {
            result[m.groupValues[1]] = unescape(m.groupValues[2])
        }
        return result
    }

    private fun q(s: String): String = "\"" + escape(s) + "\""
    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun unescape(s: String): String = s.replace("\\\"", "\"").replace("\\\\", "\\")
}
