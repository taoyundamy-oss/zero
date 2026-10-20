package com.zero.v2.services.realtime

/** TTS 句段边界策略；与引擎/播放编排解耦，便于稳定回归。 */
internal object TtsTextSegmenter {
    private val sentenceBoundary = Regex("(?<=[。！？.!？])\\s*")

    fun split(text: String): List<String> = text.split(sentenceBoundary).filter { it.isNotBlank() }
}
