package com.zero.v2.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Zero 动效 helper（U3 / 设计§7：动效仅可见且活动时播放；减少动态时全量降级）。
 *
 * 时长取 [ZeroTokens] motionMs；**减少动效**（系统"移除动画"）时即时（0ms）。
 * [durationMs] 纯函数 JVM 可测；[rememberReduceMotion] 读系统 animator duration scale。
 */
object ZeroMotion {
    /** 动效时长（reduceMotion=true → 0 即时；否则 tokens baseMs）。 */
    fun durationMs(reduceMotion: Boolean, baseMs: Int): Int = if (reduceMotion) 0 else baseMs

    /** 状态过渡动画 spec（reduceMotion → snap 即时；否则 tween(baseMs)）。 */
    fun <T> stateSpec(reduceMotion: Boolean, baseMs: Int = ZeroTokens.MOTION_STATE_MS): AnimationSpec<T> =
        if (reduceMotion) snap() else tween(durationMillis = baseMs)
}

/**
 * 读系统"减少动效"设置（animator_duration_scale == 0 → reduce motion）。
 * true 时调用方应使用 0 时长/snap spec（落实"减少动态"无障碍铁律）。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun readSetting(): Boolean =
        try {
            val scale = android.provider.Settings.Global.getFloat(
                resolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            scale == 0f
        } catch (_: Exception) {
            false
        }
    var reduced by remember(resolver) { mutableStateOf(readSetting()) }
    DisposableEffect(resolver) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = readSetting() }
        }
        resolver.registerContentObserver(
            android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        reduced = readSetting()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** Destination lifecycle also pauses animations when a different screen covers this one. */
@Composable
fun rememberMotionActive(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
}
