package com.zero.v2.app.music

import androidx.media3.common.Player

/** 播放控制参数策略，隔离 MusicModule 的播放器编排。 */
internal object MusicPlaybackPolicy {
    fun repeatMode(value: String): Int? = when (value.lowercase()) {
        "off" -> Player.REPEAT_MODE_OFF
        "all" -> Player.REPEAT_MODE_ALL
        "one" -> Player.REPEAT_MODE_ONE
        else -> null
    }
}
