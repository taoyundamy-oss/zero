package com.zero.v2.framework.memory

/** Client-owned whole paragraphs. Never split off a condition or rewrite user wording. */
internal object MemorySourceUnits {
    data class Unit(val id: String, val text: String)

    fun from(text: String): List<Unit> {
        var fenced = false
        val units = mutableListOf<Unit>()
        val paragraph = mutableListOf<String>()
        var start = 0
        fun flush() {
            if (paragraph.isEmpty()) return
            val body = paragraph.joinToString("\n")
            if (body.length > MAX_LENGTH) throw MemorySourceLimitException()
            units += Unit("p$start", body)
            paragraph.clear()
        }
        text.lineSequence().forEachIndexed { index, line ->
            val body = line.trim()
            if (body.startsWith("```") || body.startsWith("~~~")) {
                flush()
                fenced = !fenced
            } else if (fenced || body.startsWith(">") || body.isBlank()) {
                flush()
            } else {
                if (paragraph.isEmpty()) start = index
                paragraph += body
            }
        }
        flush()
        if (units.size > MAX_UNITS) throw MemorySourceLimitException()
        return units
    }

    const val MAX_LENGTH = 400
    private const val MAX_UNITS = 32
}

/** Safe error class persisted by the existing bounded retry queue, without copying source text. */
internal class MemorySourceLimitException : IllegalArgumentException("memory_source_requires_review")
