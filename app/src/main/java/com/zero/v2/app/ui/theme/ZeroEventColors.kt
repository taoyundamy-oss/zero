package com.zero.v2.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Theme-owned event accents; provenance remains explicit in the source label. */
data class ZeroEventColors(val container: Color, val content: Color, val body: Color, val meta: Color)

fun systemEventColors(scheme: ColorScheme, source: String?): ZeroEventColors {
    val accent = when (source) {
        "WECHAT_PRIVATE", "QQ_PRIVATE", "WECOM_PRIVATE" -> scheme.primary
        "SELF_DRIVE", "CLOUD_TASK", "SCHEDULED_PLAYBACK" -> scheme.tertiary
        else -> scheme.onSurfaceVariant
    }
    return ZeroEventColors(scheme.surfaceContainerLow, accent, scheme.onSurface, scheme.onSurfaceVariant)
}
