package com.zero.v2.app.ui.theme

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Zero 基础部件（U1 / 视觉规范）——按 [ZeroTokens] 构建，供 U2/U3 各屏统一消费。
 *
 * 设计§7：圆角按 tokens.radiusDp（control/composer/card/userMessage）；触控目标 ≥48dp（无障碍铁律）；
 * 配色全部走 MaterialTheme.colorScheme（不硬编码）。动效仅可见且活动时播放（U3 落地）。
 */
object ZeroSpacing {
    val xs: Dp = ZeroTokens.SPACE_XS.dp
    val s: Dp = ZeroTokens.SPACE_S.dp
    val m: Dp = ZeroTokens.SPACE_M.dp
    val l: Dp = ZeroTokens.SPACE_L.dp
    val xl: Dp = ZeroTokens.SPACE_XL.dp
    val xxl: Dp = ZeroTokens.SPACE_2XL.dp
    val xxxl: Dp = ZeroTokens.SPACE_3XL.dp
}

/** Zero 圆角形状（按 tokens.radiusDp）。 */
object ZeroShapes {
    val control = RoundedCornerShape(ZeroTokens.RADIUS_CONTROL.dp)
    val composer = RoundedCornerShape(ZeroTokens.RADIUS_COMPOSER.dp)
    val card = RoundedCornerShape(ZeroTokens.RADIUS_CARD.dp)
    val userMessage = RoundedCornerShape(ZeroTokens.RADIUS_USER_MESSAGE.dp)
}

/**
 * 强制 ≥48dp 触控目标（无障碍铁律——tokens.touchTargetMinDp=48）。
 * 用法：`Modifier.then(minTouchTarget())` 应用于可点击元素的尺寸修饰。
 */
fun Modifier.minTouchTarget(): Modifier = this.then(Modifier.sizeIn(
    minWidth = ZeroTokens.TOUCH_TARGET_MIN_DP.dp,
    minHeight = ZeroTokens.TOUCH_TARGET_MIN_DP.dp,
))

/**
 * Zero 卡片——按 tokens 圆角 + colorScheme 配色，统一 U2/U3 卡片样式。
 */
@Composable
fun ZeroCard(
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.clip(ZeroShapes.card),
        shape = ZeroShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        content = { content() },
    )
}
