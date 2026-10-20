package com.zero.v2.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class InlineMessageTextTest {
    @Test fun `markdown label and balanced URL are separate`() {
        val parts = InlineMessageText.parse("看[说明](https://example.com/a_(b)?x=1&y=2)。")
        assertEquals("看说明。", parts.joinToString("") { it.text })
        assertEquals("https://example.com/a_(b)?x=1&y=2", parts.single { it.url != null }.url)
    }
    @Test fun `bare URL excludes prose punctuation and preserves paired parentheses`() {
        val parts = InlineMessageText.parse("(https://example.com/a_(b))，https://example.org/test.")
        assertEquals(listOf("https://example.com/a_(b)", "https://example.org/test"), parts.mapNotNull { it.url })
    }
    @Test fun `code and unsafe markdown links are not actionable`() {
        val parts = InlineMessageText.parse("`https://example.com` [脚本](javascript:alert(1)) [文件](file:///tmp/a)")
        assertTrue(parts.none { it.url != null })
        assertTrue(parts.first().code)
        assertTrue(InlineMessageText.parse("`https://example.com").none { it.url != null })
    }
    @Test fun `bold links and Unicode labels retain presentation`() {
        val parts = InlineMessageText.parse("**[官网](https://example.com)**")
        assertEquals(listOf(InlineMessageText.Part("官网", "https://example.com", bold = true)), parts)
    }
    @Test fun `incomplete stream remains visible and source is unchanged`() {
        val source = "[说明](https://example.com"
        assertEquals(source, InlineMessageText.parse(source).joinToString("") { it.text })
        assertEquals(listOf("tel:123", "mailto:a@example.com"),
            InlineMessageText.parse("tel:123 mailto:a@example.com").mapNotNull { it.url })
    }
}
