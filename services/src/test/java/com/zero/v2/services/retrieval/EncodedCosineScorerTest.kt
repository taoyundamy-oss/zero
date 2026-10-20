package com.zero.v2.services.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncodedCosineScorerTest {
    @Test fun exactExistingCosineIncludingExtremeFiniteValues() {
        val inputs = listOf(floatArrayOf(1f, 2f, -3f), floatArrayOf(-1f, -2f, 3f),
            floatArrayOf(0f, 0f, 0f), floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MIN_VALUE),
            floatArrayOf(Float.MIN_VALUE, Float.MIN_VALUE, 0f))
        for (query in inputs) for (source in inputs) {
            assertEquals(RetrievalKit.cosineSimilarity(source, query),
                EncodedCosineScorer(query).score(RetrievalKit.encodeEmbedding(source))!!, 0f)
        }
    }

    @Test fun rejectsMalformedDimensionsAndNonFiniteSourcesEvenForZeroQuery() {
        val scorer = EncodedCosineScorer(floatArrayOf(0f))
        assertNull(scorer.score(byteArrayOf(1, 2, 3)))
        assertNull(scorer.score(RetrievalKit.encodeEmbedding(floatArrayOf(Float.NaN))))
        assertNull(EncodedCosineScorer(floatArrayOf(Float.POSITIVE_INFINITY))
            .score(RetrievalKit.encodeEmbedding(floatArrayOf(1f))))
        assertNull(EncodedCosineScorer(floatArrayOf()).score(byteArrayOf()))
    }

    @Test fun ownsQuerySnapshot() {
        val query = floatArrayOf(1f, 0f)
        val scorer = EncodedCosineScorer(query)
        query[0] = 0f
        assertEquals(1f, scorer.score(RetrievalKit.encodeEmbedding(floatArrayOf(1f, 0f)))!!, 0f)
    }
}
