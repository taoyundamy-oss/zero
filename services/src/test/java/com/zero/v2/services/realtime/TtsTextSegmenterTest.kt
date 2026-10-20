package com.zero.v2.services.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextSegmenterTest {
    @Test
    fun splitsChineseAndAsciiSentenceBoundaries() {
        assertEquals(listOf("你好。", "世界!", "再见？"), TtsTextSegmenter.split("你好。 世界!再见？"))
    }

    @Test
    fun ignoresBlankInputAndTrailingWhitespace() {
        assertTrue(TtsTextSegmenter.split("   \n\t").isEmpty())
        assertEquals(listOf("一句。"), TtsTextSegmenter.split("一句。   "))
    }
}
