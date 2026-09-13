package org.melodist.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 遵循 Google Android TV 官方设计指南的响应式尺寸计算系统
 * 屏幕尺寸与比例适配
 */
data class TvWindowMetrics(
    val screenWidthDp: Dp,
    val screenHeightDp: Dp,
    val horizontalSafePadding: Dp,
    val verticalSafePadding: Dp,
    val heroCardHeight: Dp,
    val playerCoverSize: Dp,
    val trackCardWidth: Dp,
)

@Composable
fun rememberTvWindowMetrics(): TvWindowMetrics {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.dp
    val height = configuration.screenHeightDp.dp

    // Google TV 官方建议的安全区 Overscan 边距：约 5%~6%
    val horizontalSafe = (width * 0.055f).coerceIn(40.dp, 80.dp)
    val verticalSafe = (height * 0.055f).coerceIn(24.dp, 56.dp)

    // 正在播放卡片高度
    val heroHeight = (height * 0.46f).coerceIn(220.dp, 360.dp)

    // 播放页封面尺寸
    val coverSize = (height * 0.52f).coerceIn(220.dp, 440.dp)

    // 推荐轨道卡片宽度：占屏幕宽度约 26%
    val trackWidth = (width * 0.26f).coerceIn(220.dp, 380.dp)

    return TvWindowMetrics(
        screenWidthDp = width,
        screenHeightDp = height,
        horizontalSafePadding = horizontalSafe,
        verticalSafePadding = verticalSafe,
        heroCardHeight = heroHeight,
        playerCoverSize = coverSize,
        trackCardWidth = trackWidth,
    )
}
