package com.zero.v2.app.ui.chat

import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.zero.v2.app.ui.theme.ZeroShapes
import com.zero.v2.core.common.Attachment
import kotlinx.coroutines.delay

/** Short, user-started attachment preview; no library entry or background playback service. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
internal fun AttachmentAudioDialog(attachment: Attachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(attachment.uri) { ExoPlayer.Builder(context).build() }
    val released = remember(player) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var ready by remember(player) { mutableStateOf(false) }
    var playing by remember(player) { mutableStateOf(false) }
    var paused by remember(player) { mutableStateOf(false) }
    var ended by remember(player) { mutableStateOf(false) }
    var error by remember(player) { mutableStateOf<String?>(null) }
    val focus = remember(player) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                if (!released.get() && change < 0) { player.pause(); paused = true }
            }, android.os.Handler(android.os.Looper.getMainLooper())).build()
    }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                ready = state == Player.STATE_READY || state == Player.STATE_ENDED
                if (state == Player.STATE_ENDED) {
                    ended = true
                    audio.abandonAudioFocusRequest(focus)
                }
            }
            override fun onPlayerError(exception: PlaybackException) {
                error = "音频无法播放：文件不可用或格式不受支持。"
                audio.abandonAudioFocusRequest(focus)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause(); paused = true
                audio.abandonAudioFocusRequest(focus)
            }
        }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        try {
            val view = attachmentViewIntent(context, attachment)
            player.setMediaItem(MediaItem.Builder().setUri(requireNotNull(view.data)).setMimeType(view.type).build())
            player.prepare()
        } catch (_: Exception) { error = "音频无法播放：文件不可用或格式不受支持。" }
        onDispose {
            released.set(true)
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
            audio.abandonAudioFocusRequest(focus)
        }
    }
    LaunchedEffect(player) {
        delay(5_000)
        if (!ready && error == null) {
            error = "音频准备超时，请关闭后重试。"
            player.stop()
        }
    }
    val status = when {
        error != null -> error!!
        !ready -> "正在准备音频"
        playing -> "正在播放"
        ended -> "播放完成"
        paused -> "已暂停"
        else -> "可播放"
    }
    AlertDialog(onDismissRequest = onDismiss, shape = ZeroShapes.card,
        title = { Text("音频预览") },
        text = { Column { Text(status, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } },
        confirmButton = {
            TextButton(enabled = ready && error == null, onClick = {
                if (playing) {
                    player.pause(); paused = true
                    audio.abandonAudioFocusRequest(focus)
                } else if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    if (ended) player.seekTo(0)
                    ended = false; paused = false
                    player.play()
                } else { error = "当前无法播放，请关闭后重试。" }
            }) { Text(if (playing) "暂停" else "播放") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
