package com.zero.v2.services.embedding

/** Pinned BGE zh v1.5: case-sensitive Bert basic tokenizer + WordPiece, no accent stripping. */
internal class BgeTokenizer(vocabulary: List<String>) {
    private val vocab = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val specials = listOf("[UNK]", "[SEP]", "[PAD]", "[CLS]", "[MASK]")

    init {
        require(specials.all { it in vocab })
    }

    private fun clean(text: String): String = buildString {
        text.take(MAX_CHARS).codePoints().forEach { cp ->
            when {
                cp == 0 || cp == REPLACEMENT -> Unit
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> append(' ')
                Character.getType(cp) in
                    setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt()) -> Unit
                chinese(cp) -> {
                    append(' ')
                    appendCodePoint(cp)
                    append(' ')
                }
                else -> appendCodePoint(cp)
            }
        }
    }

    private fun basicTokens(text: String): List<String> {
        val clean = clean(text)
        val basic = mutableListOf<String>()
        val token = StringBuilder()
        fun flush() {
            if (token.isNotEmpty()) {
                basic += token.toString()
                token.clear()
            }
        }
        var i = 0
        while (i < clean.length) {
            val special = specials.firstOrNull { clean.startsWith(it, i) }
            if (special != null) {
                flush()
                basic += special
                i += special.length
                continue
            }
            val cp = clean.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) -> flush()
                punctuation(cp) -> {
                    flush()
                    basic += String(Character.toChars(cp))
                }
                else -> token.appendCodePoint(cp)
            }
        }
        flush()
        return basic
    }

    fun encode(text: String): LongArray {
        val result = mutableListOf(vocab.getValue("[CLS]"))
        for (word in basicTokens(text)) {
            result += wordPieces(word)
            if (result.size >= CONTENT_LIMIT) break
        }
        return (result.take(CONTENT_LIMIT) + vocab.getValue("[SEP]")).toLongArray()
    }

    private fun wordPieces(word: String): List<Long> {
        val pieces = mutableListOf<Long>()
        val chars = word.codePoints().toArray()
        var start = 0
        while (start < chars.size && chars.size <= WORD_LIMIT) {
            val match = longestPiece(chars, start) ?: break
            pieces += match.second
            start = match.first
        }
        return if (start == chars.size) pieces else listOf(vocab.getValue("[UNK]"))
    }

    private fun longestPiece(chars: IntArray, start: Int): Pair<Int, Long>? {
        for (end in chars.size downTo start + 1) {
            val prefix = if (start > 0) "##" else String()
            val token = vocab[prefix + String(chars, start, end - start)]
            if (token != null) return end to token
        }
        return null
    }

    private companion object {
        const val MAX_CHARS = 16000
        const val REPLACEMENT = 0xfffd
        const val CONTENT_LIMIT = 511
        const val WORD_LIMIT = 100
    }

    // Unicode and ASCII ranges are the pinned BERT tokenizer specification.
    @Suppress("MagicNumber")
    private fun chinese(c: Int) =
        c in 0x4e00..0x9fff ||
            c in 0x3400..0x4dbf ||
            c in 0x20000..0x2a6df ||
            c in 0x2a700..0x2b73f ||
            c in 0x2b740..0x2b81f ||
            c in 0x2b820..0x2ceaf ||
            c in 0xf900..0xfaff ||
            c in 0x2f800..0x2fa1f

    @Suppress("MagicNumber")
    private fun punctuation(c: Int) =
        c in 33..47 ||
            c in 58..64 ||
            c in 91..96 ||
            c in 123..126 ||
            Character.getType(c) in
                setOf(
                    Character.CONNECTOR_PUNCTUATION.toInt(),
                    Character.DASH_PUNCTUATION.toInt(),
                    Character.START_PUNCTUATION.toInt(),
                    Character.END_PUNCTUATION.toInt(),
                    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                    Character.OTHER_PUNCTUATION.toInt())
}
