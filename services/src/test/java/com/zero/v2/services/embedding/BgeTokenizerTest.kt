package com.zero.v2.services.embedding

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BgeTokenizerTest {
    @Test
    fun officialTokenizerGoldenIncludesChineseNegationUnicodeCaseAndTruncation() {
        val loader = javaClass.classLoader!!
        val vocabulary = loader.getResourceAsStream("bge/vocab.txt")!!.bufferedReader().readLines()
        val tokenizer = BgeTokenizer(vocabulary)
        val fixtures =
            JSONArray(loader.getResourceAsStream("bge/golden.json")!!.bufferedReader().readText())
        for (i in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(i)
            val expected = fixture.getJSONArray("ids")
            assertArrayEquals(
                "fixture $i",
                LongArray(expected.length()) { expected.getLong(it) },
                tokenizer.encode(fixture.getString("text")))
        }
    }
}
