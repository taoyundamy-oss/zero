package com.zero.v2.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownBlocksTest {
    @Test fun `long fence preserves nested short fences and trailing paragraph`() {
        val source = "````markdown\n```kotlin\nval x = 1\n```\n````\n\n完成"
        assertEquals(listOf(MarkdownBlock.Code("markdown", "```kotlin\nval x = 1\n```"),
            MarkdownBlock.Paragraph("完成")), MarkdownBlocks.parse(source))
    }

    @Test fun `fence-like code text does not close code`() {
        val source = "~~~text\n~~~literal\nkeep\n~~~~  \n后续"
        assertEquals(listOf(MarkdownBlock.Code("text", "~~~literal\nkeep"),
            MarkdownBlock.Paragraph("后续")), MarkdownBlocks.parse(source))
    }

    @Test fun `unfinished long streaming fence keeps all nested content`() {
        assertEquals(listOf(MarkdownBlock.Code("markdown", "```sh\necho ok\n```")),
            MarkdownBlocks.parse("````markdown\n```sh\necho ok\n```"))
    }

    @Test fun `tool status table renders cells instead of raw separators`() {
        val source = "| 项 | 值 |\n| --- | --- |\n| modules | 33/33 |\n| termux | ok |"
        assertEquals(listOf(MarkdownBlock.Table(listOf("项", "值"),
            listOf(listOf("modules", "33/33"), listOf("termux", "ok")))), MarkdownBlocks.parse(source))
    }

    @Test fun `unfinished streaming code preserves pipes and markup verbatim`() {
        val source = "```sh\necho '**keep**' | cat"
        assertEquals(listOf(MarkdownBlock.Code("sh", "echo '**keep**' | cat")), MarkdownBlocks.parse(source))
    }

    @Test fun `escaped pipe does not split a table cell`() {
        val source = "A | B\n--- | ---\na\\|b | c"
        assertEquals(listOf(MarkdownBlock.Table(listOf("A", "B"), listOf(listOf("a|b", "c")))),
            MarkdownBlocks.parse(source))
    }
}
