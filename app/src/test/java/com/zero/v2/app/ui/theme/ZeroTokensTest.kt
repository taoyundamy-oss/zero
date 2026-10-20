package com.zero.v2.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ZeroTokens] 单测 — U1 验收：Kotlin 令牌与 `docs/refined/assets/zero-ui-v2/tokens.json` 一致。
 *
 * 设计源变更时本测试同步——防止代码漂离设计令牌。
 */
class ZeroTokensTest {

    @Test
    fun spaceDp_matchesTokensJson() {
        // tokens.json: "spaceDp":[4,8,12,16,24,32,48]
        assertEquals(listOf(4, 8, 12, 16, 24, 32, 48), ZeroTokens.spaceDp.toList())
        assertEquals(4, ZeroTokens.SPACE_XS)
        assertEquals(8, ZeroTokens.SPACE_S)
        assertEquals(12, ZeroTokens.SPACE_M)
        assertEquals(16, ZeroTokens.SPACE_L)
        assertEquals(24, ZeroTokens.SPACE_XL)
        assertEquals(32, ZeroTokens.SPACE_2XL)
        assertEquals(48, ZeroTokens.SPACE_3XL)
    }

    @Test
    fun radiusDp_matchesTokensJson() {
        // tokens.json: "radiusDp":{"control":16,"composer":28,"card":20,"userMessage":24}
        assertEquals(16, ZeroTokens.RADIUS_CONTROL)
        assertEquals(28, ZeroTokens.RADIUS_COMPOSER)
        assertEquals(20, ZeroTokens.RADIUS_CARD)
        assertEquals(24, ZeroTokens.RADIUS_USER_MESSAGE)
    }

    @Test
    fun touchTarget_matchesAndAccessible() {
        // tokens.json: "touchTargetMinDp":48（无障碍铁律 ≥48dp）
        assertEquals(48, ZeroTokens.TOUCH_TARGET_MIN_DP)
        assertTrue(ZeroTokens.TOUCH_TARGET_MIN_DP >= 48)
    }

    @Test
    fun typographySp_matchesTokensJson() {
        // tokens.json: "typographySp":{"body":16,"bodyLineHeight":25,"caption":12,"title":22,"headline":30}
        assertEquals(16, ZeroTokens.TYPO_BODY_SP)
        assertEquals(25, ZeroTokens.TYPO_BODY_LINE_HEIGHT_SP)
        assertEquals(12, ZeroTokens.TYPO_CAPTION_SP)
        assertEquals(22, ZeroTokens.TYPO_TITLE_SP)
        assertEquals(30, ZeroTokens.TYPO_HEADLINE_SP)
    }

    @Test
    fun motionMs_matchesTokensJson() {
        // tokens.json: "motionMs":{"feedback":120,"state":180,"sheet":280,"pane":320,"activity":1800}
        assertEquals(120, ZeroTokens.MOTION_FEEDBACK_MS)
        assertEquals(180, ZeroTokens.MOTION_STATE_MS)
        assertEquals(280, ZeroTokens.MOTION_SHEET_MS)
        assertEquals(320, ZeroTokens.MOTION_PANE_MS)
        assertEquals(1800, ZeroTokens.MOTION_ACTIVITY_MS)
    }

    @Test
    fun easingStandard_matchesTokensJson() {
        // tokens.json: "motionEasing":{"standard":[0.2,0,0,1],"exit":[0.4,0,1,1]}
        assertEquals(4, ZeroTokens.EASING_STANDARD.size)
        assertEquals(0.2f, ZeroTokens.EASING_STANDARD[0], 0.0001f)
        assertEquals(0f, ZeroTokens.EASING_STANDARD[1], 0.0001f)
        assertEquals(0f, ZeroTokens.EASING_STANDARD[2], 0.0001f)
        assertEquals(1f, ZeroTokens.EASING_STANDARD[3], 0.0001f)
        assertEquals(4, ZeroTokens.EASING_EXIT.size)
        assertEquals(0.4f, ZeroTokens.EASING_EXIT[0], 0.0001f)
        assertEquals(1f, ZeroTokens.EASING_EXIT[3], 0.0001f)
    }

    @Test
    fun reducedMotion_matchesTokensJson() {
        // tokens.json: "reducedMotion":{"translation":0,"scale":1,"loop":false,"crossfadeMs":0}
        assertEquals(0, ZeroTokens.REDUCED_MOTION_TRANSLATION)
        assertEquals(1, ZeroTokens.REDUCED_MOTION_SCALE)
        assertEquals(false, ZeroTokens.REDUCED_MOTION_LOOP)
        assertEquals(0, ZeroTokens.REDUCED_MOTION_CROSSFADE_MS)
    }

    @Test
    fun lightPalette_matchesTokensJson() {
        // tokens.json light: canvas/surface/subtle/text/muted/outline/accent/onAccent/error
        assertEquals("#FAFAF8", ZeroTokens.Light.CANVAS)
        assertEquals("#FFFFFF", ZeroTokens.Light.SURFACE)
        assertEquals("#EFEFEC", ZeroTokens.Light.SUBTLE)
        assertEquals("#17191B", ZeroTokens.Light.TEXT)
        assertEquals("#62666A", ZeroTokens.Light.MUTED)
        assertEquals("#DADCD8", ZeroTokens.Light.OUTLINE)
        assertEquals("#147A58", ZeroTokens.Light.ACCENT)
        assertEquals("#FFFFFF", ZeroTokens.Light.ON_ACCENT)
        assertEquals("#B3261E", ZeroTokens.Light.ERROR)
    }

    @Test
    fun darkPalette_matchesTokensJson() {
        // tokens.json dark
        assertEquals("#121416", ZeroTokens.Dark.CANVAS)
        assertEquals("#1C1F22", ZeroTokens.Dark.SURFACE)
        assertEquals("#292D30", ZeroTokens.Dark.SUBTLE)
        assertEquals("#F4F5F2", ZeroTokens.Dark.TEXT)
        assertEquals("#B5BABD", ZeroTokens.Dark.MUTED)
        assertEquals("#42484B", ZeroTokens.Dark.OUTLINE)
        assertEquals("#78D8AF", ZeroTokens.Dark.ACCENT)
        assertEquals("#063826", ZeroTokens.Dark.ON_ACCENT)
        assertEquals("#FFB4AB", ZeroTokens.Dark.ERROR)
    }
}
