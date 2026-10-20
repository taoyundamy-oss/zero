package com.zero.v2.services.termux

import java.io.DataInputStream
import java.io.InputStream

internal object SupervisorFrame {
    const val READ_TIMEOUT_MS = 4_000
    private const val MAX_FRAME_BYTES = 1024 * 1024
    fun read(input: InputStream): ByteArray {
        val stream = DataInputStream(input)
        val length = stream.readInt()
        require(length in 1..MAX_FRAME_BYTES) { "Invalid supervisor frame length" }
        return ByteArray(length).also { stream.readFully(it) }
    }
}
