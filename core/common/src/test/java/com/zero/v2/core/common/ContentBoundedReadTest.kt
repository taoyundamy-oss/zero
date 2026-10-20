package com.zero.v2.core.common

import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ContentBoundedReadTest {
    @Test fun `unbounded media stream is stopped before full allocation`() {
        var bytes = 0L
        val stream = object : InputStream() {
            override fun read(): Int { bytes++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                bytes += len
                b.fill(1, off, off + len)
                return len
            }
        }
        val data = ContentLimits.readBounded(stream)
        assertEquals(ContentLimits.MAX_BYTES + 1, bytes)
        assertTrue(ContentLimits.classify("image/png", data) is ReadOutcome.Oversize)
    }
}
