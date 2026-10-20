package com.zero.v2.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ZeroAdaptiveLayout] 单测 — U2 验收：compact/fold 断点逻辑（纯函数 JVM 可测）。
 *
 * Material3 WindowSizeClass 断点：Compact <600 / Medium 600-839 / Expanded ≥840。
 * Dp 相关 API（chatMaxWidth）由 Compose 运行时消费，此处只测纯分类逻辑 + 常量。
 */
class ZeroAdaptiveLayoutTest {

    @Test
    fun compactPhone_classifiesCompact() {
        assertEquals(ZeroWidthClass.COMPACT, ZeroAdaptiveLayout.widthClass(360))
        assertEquals(ZeroWidthClass.COMPACT, ZeroAdaptiveLayout.widthClass(0))
        assertEquals(ZeroWidthClass.COMPACT, ZeroAdaptiveLayout.widthClass(599))
    }

    @Test
    fun mediumTablet_classifiesMedium() {
        assertEquals(ZeroWidthClass.MEDIUM, ZeroAdaptiveLayout.widthClass(600))
        assertEquals(ZeroWidthClass.MEDIUM, ZeroAdaptiveLayout.widthClass(839))
    }

    @Test
    fun expandedFold_classifiesExpanded() {
        assertEquals(ZeroWidthClass.EXPANDED, ZeroAdaptiveLayout.widthClass(840))
        assertEquals(ZeroWidthClass.EXPANDED, ZeroAdaptiveLayout.widthClass(1080))
        // Z Fold3 展开内屏主显示区约 768-840+ dp（依据折叠姿态/分屏）
        assertEquals(ZeroWidthClass.EXPANDED, ZeroAdaptiveLayout.widthClass(900))
    }

    @Test
    fun boundaries_areExclusive() {
        // 599=Compact, 600=Medium, 839=Medium, 840=Expanded（断点边界无重叠）
        assertEquals(ZeroWidthClass.COMPACT, ZeroAdaptiveLayout.widthClass(599))
        assertEquals(ZeroWidthClass.MEDIUM, ZeroAdaptiveLayout.widthClass(600))
        assertEquals(ZeroWidthClass.MEDIUM, ZeroAdaptiveLayout.widthClass(839))
        assertEquals(ZeroWidthClass.EXPANDED, ZeroAdaptiveLayout.widthClass(840))
    }

    @Test
    fun chatMaxWidthConstant_isSensible() {
        // 折叠展开聊天内容最大宽度——避免单行过长（720dp ≈ 可读行宽上限）
        assertEquals(720, ZeroAdaptiveLayout.CHAT_MAX_WIDTH_DP)
    }

    @Test
    fun breakpoints_matchMaterial3() {
        // Material3 WindowSizeClass 官方断点：Compact <600 / Medium 600-839 / Expanded ≥840
        assertEquals(599, ZeroAdaptiveLayout.COMPACT_MAX_DP)
        assertEquals(839, ZeroAdaptiveLayout.MEDIUM_MAX_DP)
    }
}
