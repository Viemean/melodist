package org.melodist.tv.ui.theme

import androidx.compose.ui.graphics.Color

object MelodistColors {
    // 主基调与强调色：亮青绿
    val AccentGreen = Color(0xFF1EE191)

    // 收藏高亮色：暖红
    val FavoriteRed = Color(0xFFFF6482)

    // 遥控器焦点高反差边框色：全局统一水鸭青
    val FocusTeal = Color(0xFF1EE191)
    val FocusGold = Color(0xFF1EE191) // 保持别名兼容

    // 常态暗色背景容器 (默认中立冷暗色)
    val ContainerDark = Color(0xFF161F2C)
    val ContainerDarkSecondary = Color(0xFF1E2A3B)
    val SurfaceDark = Color(0xFF121214)

    // 文本颜色梯度
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFB0B0B0)
    val TextMuted = Color(0xFF6E6E6E)

    // 进度条槽底色
    val ProgressTrack = Color(0x33FFFFFF)

    // 音质徽章色
    val QualityGoldBg = Color(0x33FFB919)
    val QualityGoldBorder = Color(0x88FFB919)
    val QualityGoldText = Color(0xFFFFD166)
}
