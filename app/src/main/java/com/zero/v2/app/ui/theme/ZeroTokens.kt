package com.zero.v2.app.ui.theme

/**
 * Zero 设计令牌（U1 / 视觉规范）——`docs/refined/assets/zero-ui-v2/tokens.json` 的 Kotlin 投影。
 *
 * 纯常量（无 Compose/Android 类型依赖）→ JVM 可单测（[ZeroTokensTest] 校验与 tokens.json 一致）。
 * Theme/基础部件消费时 `.dp`/`.sp`/`Color(hex)` 转换。设计源变更 → 同步本对象 + 测试。
 *
 * 设计§7 / 视觉规范：48dp 触控、字体 200%（系统字号缩放）、TalkBack、减少动态；
 * 动效仅可见且活动时播放；流式 token 不反复动画。
 */
object ZeroTokens {
    // ═══ 间距（spaceDp 数组，4-48 阶梯）═══
    val spaceDp: IntArray = intArrayOf(4, 8, 12, 16, 24, 32, 48)
    const val SPACE_XS = 4
    const val SPACE_S = 8
    const val SPACE_M = 12
    const val SPACE_L = 16
    const val SPACE_XL = 24
    const val SPACE_2XL = 32
    const val SPACE_3XL = 48

    // ═══ 圆角（radiusDp）═══
    const val RADIUS_CONTROL = 16
    const val RADIUS_COMPOSER = 28
    const val RADIUS_CARD = 20
    const val RADIUS_USER_MESSAGE = 24

    // ═══ 触控目标最小尺寸（touchTargetMinDp=48，无障碍铁律）═══
    const val TOUCH_TARGET_MIN_DP = 48

    // ═══ 排版（typographySp）═══
    const val TYPO_BODY_SP = 16
    const val TYPO_BODY_LINE_HEIGHT_SP = 25
    const val TYPO_CAPTION_SP = 12
    const val TYPO_TITLE_SP = 22
    const val TYPO_HEADLINE_SP = 30

    // ═══ 动效时长（motionMs）═══
    const val MOTION_FEEDBACK_MS = 120
    const val MOTION_STATE_MS = 180
    const val MOTION_SHEET_MS = 280
    const val MOTION_PANE_MS = 320
    const val MOTION_ACTIVITY_MS = 1800

    // ═══ 动效缓动控制点（motionEasing.standard=[0.2,0,0,1] / exit=[0.4,0,1,1]）═══
    val EASING_STANDARD: FloatArray = floatArrayOf(0.2f, 0f, 0f, 1f)
    val EASING_EXIT: FloatArray = floatArrayOf(0.4f, 0f, 1f, 1f)

    // ═══ 减少动效（reducedMotion——无障碍"减少动态"时全量覆盖）═══
    const val REDUCED_MOTION_TRANSLATION = 0
    const val REDUCED_MOTION_SCALE = 1
    const val REDUCED_MOTION_LOOP = false
    const val REDUCED_MOTION_CROSSFADE_MS = 0

    /** 亮色调色板（hex，与 tokens.json light 一致）。 */
    object Light {
        const val CANVAS = "#FAFAF8"
        const val SURFACE = "#FFFFFF"
        const val SUBTLE = "#EFEFEC"
        const val TEXT = "#17191B"
        const val MUTED = "#62666A"
        const val OUTLINE = "#DADCD8"
        const val ACCENT = "#147A58"
        const val ON_ACCENT = "#FFFFFF"
        const val ERROR = "#B3261E"
    }

    /** 暗色调色板（hex，与 tokens.json dark 一致）。 */
    object Dark {
        const val CANVAS = "#121416"
        const val SURFACE = "#1C1F22"
        const val SUBTLE = "#292D30"
        const val TEXT = "#F4F5F2"
        const val MUTED = "#B5BABD"
        const val OUTLINE = "#42484B"
        const val ACCENT = "#78D8AF"
        const val ON_ACCENT = "#063826"
        const val ERROR = "#FFB4AB"
    }
}
