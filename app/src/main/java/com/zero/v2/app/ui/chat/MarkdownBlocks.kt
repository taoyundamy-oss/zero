package com.zero.v2.app.ui.chat

internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
}

internal object MarkdownBlocks {
    fun parse(text: String): List<MarkdownBlock> {
        val lines = text.lines()
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            if (line.isBlank()) { index++; continue }
            val opening = openingFence(trimmed)
            if (opening != null) {
                val fence = opening.first
                val language = opening.second
                val body = mutableListOf<String>()
                index++
                while (index < lines.size && !closesFence(lines[index], fence)) body += lines[index++]
                if (index < lines.size) index++
                blocks += MarkdownBlock.Code(language, body.joinToString("\n"))
            } else if (index + 1 < lines.size && isSeparator(lines[index + 1])) {
                val headers = cells(line)
                val rows = mutableListOf<List<String>>()
                index += 2
                while (index < lines.size && lines[index].isNotBlank() && '|' in lines[index]) {
                    val row = cells(lines[index++])
                    rows += headers.indices.map { row.getOrElse(it) { "" } }
                }
                blocks += MarkdownBlock.Table(headers, rows)
            } else {
                val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)
                if (heading != null) {
                    blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                    index++
                } else {
                    val body = mutableListOf(line)
                    index++
                    while (index < lines.size && lines[index].isNotBlank() &&
                        openingFence(lines[index].trimStart()) == null &&
                        !lines[index].trimStart().startsWith("#") &&
                        !(index + 1 < lines.size && isSeparator(lines[index + 1]))) body += lines[index++]
                    blocks += MarkdownBlock.Paragraph(body.joinToString("\n"))
                }
            }
        }
        return blocks
    }

    private fun openingFence(line: String): Pair<String, String>? {
        val marker = line.firstOrNull() ?: return null
        if (marker != '`' && marker != '~') return null
        val fence = line.takeWhile { it == marker }
        if (fence.length < 3) return null
        val info = line.drop(fence.length).trim()
        if (marker == '`' && '`' in info) return null
        return fence to info
    }

    private fun closesFence(line: String, fence: String): Boolean {
        val candidate = line.trimStart()
        val length = candidate.takeWhile { it == fence.first() }.length
        return length >= fence.length && candidate.drop(length).isBlank()
    }

    private fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|")
        .split(Regex("(?<!\\\\)\\|" )).map { it.trim().replace("\\|", "|") }

    private fun isSeparator(line: String): Boolean =
        '|' in line && cells(line).all { it.matches(Regex(":?-{3,}:?")) }
}
