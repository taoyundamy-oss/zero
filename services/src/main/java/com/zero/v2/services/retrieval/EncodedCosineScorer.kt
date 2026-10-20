package com.zero.v2.services.retrieval

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Exact cosine over the existing little-endian blob, without allocating a float array per indexed row. */
class EncodedCosineScorer(query: FloatArray) {
    private val query = query.copyOf()
    private val validQuery = query.isNotEmpty() && query.all { it.isFinite() }
    private val queryNorm = sqrt(query.fold(0.0) { sum, value -> sum + value.toDouble() * value.toDouble() })

    fun score(blob: ByteArray): Float? =
        if (!validQuery || blob.size != query.size * Float.SIZE_BYTES) null else finiteScore(blob)

    private fun finiteScore(blob: ByteArray): Float? {
        val stored = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        var dot = 0.0
        var squared = 0.0
        for (value in query) {
            val source = stored.float
            if (!source.isFinite()) return null
            val number = source.toDouble()
            dot += number * value.toDouble()
            squared += number * number
        }
        val denominator = sqrt(squared) * queryNorm
        return if (denominator > 0.0) (dot / denominator).coerceIn(-1.0, 1.0).toFloat() else 0f
    }
}
