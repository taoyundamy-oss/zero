package com.zero.v2.core.common

/** Explicit task fields in the existing content column; no inferred status or schema migration. */
data class TaskMemory(val status: String, val next: String, val acceptance: String,
    val pointer: String?, val text: String) {
    init {
        require(status in STATUSES) { "task_status" }
        require(next.isNotBlank() && acceptance.isNotBlank() && text.isNotBlank()) { "task_fields_required" }
        require(listOf(next, acceptance, pointer.orEmpty()).all { it.length <= FIELD_LIMIT && '\n' !in it && '\r' !in it }) {
            "task_fields_single_line"
        }
    }
    val active: Boolean get() = status == "ACTIVE" || status == "WAITING"
    fun encode(): String = "${header(status)}下一步：$next\n验收：$acceptance\n指针：${pointer.orEmpty()}\n\n$text"

    companion object {
        val STATUSES = listOf("ACTIVE", "WAITING", "DONE", "CANCELLED")
        private const val PREFIX = "[zero-task:v1 status="
        private const val FIELD_LIMIT = 500
        fun header(status: String): String = "$PREFIX$status]\n"
        fun decode(content: String): TaskMemory? = runCatching {
            val lines = content.split('\n')
            require(lines.size >= 6 && lines[0].startsWith(PREFIX) && lines[0].endsWith("]"))
            require(lines[1].startsWith("下一步：") && lines[2].startsWith("验收：") && lines[3].startsWith("指针：") && lines[4].isEmpty())
            TaskMemory(lines[0].removePrefix(PREFIX).removeSuffix("]"), lines[1].removePrefix("下一步："),
                lines[2].removePrefix("验收："), lines[3].removePrefix("指针：").ifBlank { null }, lines.drop(5).joinToString("\n"))
        }.getOrNull()
    }
}
