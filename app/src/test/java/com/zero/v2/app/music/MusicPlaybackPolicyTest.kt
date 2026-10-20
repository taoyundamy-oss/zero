package com.zero.v2.app.music

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicPlaybackPolicyTest {
    @Test
    fun mapsRepeatModes() {
        assertEquals(Player.REPEAT_MODE_OFF, MusicPlaybackPolicy.repeatMode("OFF"))
        assertEquals(Player.REPEAT_MODE_ALL, MusicPlaybackPolicy.repeatMode("all"))
        assertEquals(Player.REPEAT_MODE_ONE, MusicPlaybackPolicy.repeatMode("one"))
        assertNull(MusicPlaybackPolicy.repeatMode("invalid"))
    }
}
