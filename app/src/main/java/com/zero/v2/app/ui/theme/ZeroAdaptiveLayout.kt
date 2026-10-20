package com.zero.v2.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Zero 自适应布局（U2 / 设计§7：依据窗口大小/折叠姿态布局）。
 *
 * 纯函数 [widthClass] 按 Material3 WindowSizeClass 断点分类（Compact/Medium/Expanded），
 * JVM 可单测；[rememberZeroWidthClass] 在 Compose 中读 LocalConfiguration.screenWidthDp。
 *
 * 用法：EXPANDED（折叠屏展开/大屏）→ 内容居中 + 最大宽度约束（避免文本行过长、
 * composer 横跨整屏）；COMPACT（手机竖屏）→ 全宽。MEDIUM（小平板/折叠主屏）→ 全宽。
 */
object ZeroAdaptiveLayout {
    // Material3 WindowSizeClass 断点（dp）：Compact <600 / Medium 600-839 / Expanded ≥840
    const val COMPACT_MAX_DP = 599
    const val MEDIUM_MAX_DP = 839

    /** 折叠屏展开/大屏聊天内容最大宽度（dp）——避免单行过长影响可读性。 */
    const val CHAT_MAX_WIDTH_DP = 720

    /** 按屏幕宽度 dp 分类窗口尺寸。 */
    fun widthClass(widthDp: Int): ZeroWidthClass = when {
        widthDp <= COMPACT_MAX_DP -> ZeroWidthClass.COMPACT
        widthDp <= MEDIUM_MAX_DP -> ZeroWidthClass.MEDIUM
        else -> ZeroWidthClass.EXPANDED
    }

    /** EXPANDED 时聊天内容最大宽度（Dp）；COMPACT/MEDIUM 返回 null（全宽）。 */
    fun chatMaxWidth(widthDp: Int): Dp? =
        if (widthClass(widthDp) == ZeroWidthClass.EXPANDED) CHAT_MAX_WIDTH_DP.dp else null
}

/** 窗口尺寸档位（Compact 手机 / Medium 平板 / Expanded 折叠展开+大屏）。 */
enum class ZeroWidthClass { COMPACT, MEDIUM, EXPANDED }

/** Compose 中记忆当前窗口尺寸档位（读 LocalConfiguration.screenWidthDp）。 */
@Composable
fun rememberZeroWidthClass(): ZeroWidthClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) { ZeroAdaptiveLayout.widthClass(widthDp) }
}
