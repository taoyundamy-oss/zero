package com.zero.v2.services.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxCommandPolicyTest {
    @Test
    fun sanitizeRemovesPathAndUnsafeCharacters() {
        assertEquals("a_b_.txt", TermuxCommandPolicy.sanitizeFileName("../folder/a:b?.txt"))
    }

    @Test
    fun sanitizeBoundsLengthAndGeneratesBlankFallback() {
        assertTrue(TermuxCommandPolicy.sanitizeFileName("x".repeat(200)).length <= 120)
        assertTrue(TermuxCommandPolicy.sanitizeFileName("../").startsWith("linux_"))
    }

    @Test
    fun urlEncodeEscapesQueryDelimiters() {
        assertEquals("a+b%26c", TermuxCommandPolicy.urlEncode("a b&c"))
    }
}
