package com.zero.v2.services.termux

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorFrameTest {
    @Test fun rejectsOversizedAndInvalidFramesBeforeReadingPayload() {
        for (length in listOf(-1, 0, 1048577)) {
            val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(length) }.toByteArray()
            val failure = runCatching { SupervisorFrame.read(ByteArrayInputStream(bytes)) }.exceptionOrNull()
            assertTrue("length=$length error=$failure", failure is IllegalArgumentException)
        }
    }

    @Test fun validFrameAndTruncatedPayloadRemainDistinct() {
        val bytes = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(3); write(byteArrayOf(1, 2, 3)) }
        }.toByteArray()
        assertArrayEquals(byteArrayOf(1, 2, 3), SupervisorFrame.read(ByteArrayInputStream(bytes)))
        assertTrue(runCatching { SupervisorFrame.read(ByteArrayInputStream(bytes.copyOf(6))) }
            .exceptionOrNull() is java.io.EOFException)
    }
}
