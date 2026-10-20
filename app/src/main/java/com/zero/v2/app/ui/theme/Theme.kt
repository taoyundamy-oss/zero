package com.zero.v2.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Zero 主题层 — U1（视觉规范：tokens 统一 + 双色板 + 动态色回退）。
 *
 * - **tokens 投影**：调色板/排版对齐 `docs/refined/assets/zero-ui-v2/tokens.json`
 *   （[ZeroTokens] 纯常量 + 本文件 Color/Typography 转换）。
 * - **Dynamic Color**：Android 12+ 取系统取色（Material You），跟随壁纸（用户可关）。
 * - **品牌回退**：手写 Zero 双色板（tokens.json light/dark——青碧 accent + 暖白 canvas）。
 * - **暗色**：跟随系统 isSystemInDarkTheme；各屏配色全部走 colorScheme（U2/U3 落地）。
 * - **排版**：[zeroTypography] 按 tokens.json（body 16/行高 25、caption 12、title 22、headline 30）。
 * - **Edge-to-Edge**：由 MainActivity.enableEdgeToEdge() 承担（U2 逐屏补 WindowInsets）。
 */
@Composable
fun ZeroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ZeroDarkColors
        else -> ZeroLightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = zeroTypography,
        shapes = androidx.compose.material3.Shapes(
            small = ZeroShapes.control, medium = ZeroShapes.card,
            large = ZeroShapes.card, extraLarge = ZeroShapes.composer),
        content = content
    )
}

/** hex 字符串（tokens.json 源）→ Compose Color（android.graphics.Color.parseColor）。 */
private fun c(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

/** Zero 品牌亮色板（U1——tokens.json light：青碧 accent + 暖白 canvas）。 */
private val ZeroLightColors = lightColorScheme(
    primary = c(ZeroTokens.Light.ACCENT),
    onPrimary = c(ZeroTokens.Light.ON_ACCENT),
    primaryContainer = c(ZeroTokens.Light.SUBTLE),
    onPrimaryContainer = c(ZeroTokens.Light.TEXT),
    secondary = c(ZeroTokens.Light.MUTED),
    onSecondary = c(ZeroTokens.Light.ON_ACCENT),
    secondaryContainer = c(ZeroTokens.Light.SUBTLE),
    onSecondaryContainer = c(ZeroTokens.Light.TEXT),
    tertiary = c(ZeroTokens.Light.ACCENT),
    onTertiary = c(ZeroTokens.Light.ON_ACCENT),
    tertiaryContainer = c(ZeroTokens.Light.SUBTLE),
    onTertiaryContainer = c(ZeroTokens.Light.TEXT),
    error = c(ZeroTokens.Light.ERROR),
    onError = c(ZeroTokens.Light.ON_ACCENT),
    background = c(ZeroTokens.Light.CANVAS),
    onBackground = c(ZeroTokens.Light.TEXT),
    surface = c(ZeroTokens.Light.CANVAS),
    onSurface = c(ZeroTokens.Light.TEXT),
    surfaceVariant = c(ZeroTokens.Light.SUBTLE),
    onSurfaceVariant = c(ZeroTokens.Light.MUTED),
    outline = c(ZeroTokens.Light.OUTLINE),
    outlineVariant = c(ZeroTokens.Light.OUTLINE),
    surfaceContainerLowest = c(ZeroTokens.Light.SURFACE),
    surfaceContainerLow = c(ZeroTokens.Light.SURFACE),
    surfaceContainer = c(ZeroTokens.Light.SUBTLE),
    surfaceContainerHigh = c(ZeroTokens.Light.SUBTLE),
    surfaceContainerHighest = c(ZeroTokens.Light.SUBTLE),
)

/** Zero 品牌暗色板（U1——tokens.json dark：青碧 accent + 深炭 canvas）。 */
private val ZeroDarkColors = darkColorScheme(
    primary = c(ZeroTokens.Dark.ACCENT),
    onPrimary = c(ZeroTokens.Dark.ON_ACCENT),
    primaryContainer = c(ZeroTokens.Dark.SUBTLE),
    onPrimaryContainer = c(ZeroTokens.Dark.TEXT),
    secondary = c(ZeroTokens.Dark.MUTED),
    onSecondary = c(ZeroTokens.Dark.ON_ACCENT),
    secondaryContainer = c(ZeroTokens.Dark.SUBTLE),
    onSecondaryContainer = c(ZeroTokens.Dark.TEXT),
    tertiary = c(ZeroTokens.Dark.ACCENT),
    onTertiary = c(ZeroTokens.Dark.ON_ACCENT),
    tertiaryContainer = c(ZeroTokens.Dark.SUBTLE),
    onTertiaryContainer = c(ZeroTokens.Dark.TEXT),
    error = c(ZeroTokens.Dark.ERROR),
    onError = c(ZeroTokens.Dark.ON_ACCENT),
    background = c(ZeroTokens.Dark.CANVAS),
    onBackground = c(ZeroTokens.Dark.TEXT),
    surface = c(ZeroTokens.Dark.SURFACE),
    onSurface = c(ZeroTokens.Dark.TEXT),
    surfaceVariant = c(ZeroTokens.Dark.SUBTLE),
    onSurfaceVariant = c(ZeroTokens.Dark.MUTED),
    outline = c(ZeroTokens.Dark.OUTLINE),
    outlineVariant = c(ZeroTokens.Dark.OUTLINE),
    surfaceContainerLowest = c(ZeroTokens.Dark.CANVAS),
    surfaceContainerLow = c(ZeroTokens.Dark.SURFACE),
    surfaceContainer = c(ZeroTokens.Dark.SURFACE),
    surfaceContainerHigh = c(ZeroTokens.Dark.SUBTLE),
    surfaceContainerHighest = c(ZeroTokens.Dark.SUBTLE),
)

/**
 * Zero 排版（U1——tokens.json typographySp：body 16/行高 25、caption 12、title 22、headline 30）。
 * 系统字号缩放（字体 200%）由 Compose 自动随 LocalDensity.fontScale 放大，无需手动乘。
 */
private val zeroTypography = Typography(
    headlineMedium = TextStyle(fontSize = ZeroTokens.TYPO_HEADLINE_SP.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = (ZeroTokens.TYPO_HEADLINE_SP - 4).sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = ZeroTokens.TYPO_TITLE_SP.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = (ZeroTokens.TYPO_TITLE_SP - 4).sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = (ZeroTokens.TYPO_TITLE_SP - 8).sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(
        fontSize = ZeroTokens.TYPO_BODY_SP.sp,
        lineHeight = ZeroTokens.TYPO_BODY_LINE_HEIGHT_SP.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = (ZeroTokens.TYPO_BODY_SP - 2).sp,
        lineHeight = (ZeroTokens.TYPO_BODY_LINE_HEIGHT_SP - 3).sp,
    ),
    bodySmall = TextStyle(
        fontSize = (ZeroTokens.TYPO_BODY_SP - 4).sp,
        lineHeight = (ZeroTokens.TYPO_BODY_LINE_HEIGHT_SP - 6).sp,
    ),
    labelLarge = TextStyle(fontSize = (ZeroTokens.TYPO_BODY_SP - 2).sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = ZeroTokens.TYPO_CAPTION_SP.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = (ZeroTokens.TYPO_CAPTION_SP - 1).sp),
)
