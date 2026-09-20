package org.melodist.tv.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import org.melodist.tv.ui.theme.LocalMonetSurface
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.MonetColorExtractor
import org.melodist.tv.ui.theme.toMonetContainer

/**
 * 统一卡片候选数据结构
 */
data class RotatingCardItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String = "",
    val albumMid: String = "",
    val songMid: String = "",
    val defaultBgColor: Color = Color(0xFF1A2234),
    val badgeText: String = "",
)

/**
 * 通用平滑轮换卡片组件：支持水平滑出滑入 + 渐显渐隐 + 动态 Monet 取色
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RotatingTrackCard(
    item: RotatingCardItem,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
    slotIndex: Int = 0,
    showPlayButton: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    val cardHeight = cardWidth * 1.22f

    // Material You 规范：卡片底色基于全局环境表面色推导统一的深色容器层 (Container Surface)
    val globalMonetSurface = LocalMonetSurface.current
    val cardContainerColor =
        remember(globalMonetSurface) {
            globalMonetSurface.toMonetContainer(0.06f)
        }
    val animatedCardBg by animateColorAsState(
        targetValue = cardContainerColor,
        animationSpec = tween(600),
        label = "RotatingCardContainerColor",
    )

    // 单图 Monet 取色：作为点睛强调色 (Dynamic Accent) 赋予 Badge 与播放按钮
    var dynamicCoverColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(item.coverUrl) {
        if (item.coverUrl.isNotBlank()) {
            dynamicCoverColor = MonetColorExtractor.extractFromUrl(item.coverUrl)
        }
    }

    // 点睛强调色：提取封面色相，提升明度呈现高亮活力质感
    val accentColor =
        remember(dynamicCoverColor) {
            val base = dynamicCoverColor ?: Color(0xFFB4F8FF)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(base.toArgb(), hsv)
            hsv[1] = (hsv[1] * 0.90f).coerceIn(0.35f, 0.75f)
            hsv[2] = 0.95f
            Color(android.graphics.Color.HSVToColor(hsv))
        }
    val animatedAccentColor by animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(600),
        label = "RotatingAccentColor",
    )

    // 点睛容器底色：微暗同色系底色，用于徽章胶囊背景
    val accentContainerColor =
        remember(dynamicCoverColor) {
            val base = dynamicCoverColor ?: Color(0xFF1E2E40)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(base.toArgb(), hsv)
            hsv[1] = (hsv[1] * 0.70f).coerceIn(0.25f, 0.50f)
            hsv[2] = 0.35f
            Color(android.graphics.Color.HSVToColor(hsv))
        }
    val animatedAccentContainer by animateColorAsState(
        targetValue = accentContainerColor,
        animationSpec = tween(600),
        label = "RotatingAccentContainer",
    )

    // 播放按钮深色前景图标色，保证高对比度
    val accentOnColor =
        remember(dynamicCoverColor) {
            val base = dynamicCoverColor ?: Color(0xFF0F172A)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(base.toArgb(), hsv)
            hsv[1] = (hsv[1] * 1.20f).coerceIn(0.60f, 0.95f)
            hsv[2] = 0.18f
            Color(android.graphics.Color.HSVToColor(hsv))
        }
    val animatedAccentOnColor by animateColorAsState(
        targetValue = accentOnColor,
        animationSpec = tween(600),
        label = "RotatingAccentOnColor",
    )

    Card(
        onClick = onClick,
        modifier =
            modifier
                .width(cardWidth)
                .height(cardHeight)
                .onFocusChanged { state ->
                    onFocusChanged(state.isFocused)
                },
        shape =
            CardDefaults.shape(
                shape = MelodistShapes.CardCorner,
                focusedShape = MelodistShapes.CardCorner,
            ),
        colors =
            CardDefaults.colors(
                containerColor = animatedCardBg.copy(alpha = 0.88f),
                focusedContainerColor = animatedCardBg,
            ),
        border =
            CardDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        shape = MelodistShapes.CardCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(3.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.CardCorner,
                    ),
            ),
        scale =
            CardDefaults.scale(
                focusedScale = 1.05f,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(animatedCardBg),
        ) {
            // 上半部：专辑封面展示（画幅增大至 80%，更加饱满充盈）
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(cardHeight * 0.68f)
                        .padding(top = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = item,
                    transitionSpec = {
                        (
                            slideInHorizontally(
                                animationSpec = tween(durationMillis = 900, delayMillis = (slotIndex * 80).coerceAtMost(400), easing = FastOutSlowInEasing),
                                initialOffsetX = { fullWidth -> (fullWidth * 0.45f).toInt() },
                            ) +
                                fadeIn(
                                    animationSpec = tween(durationMillis = 800, delayMillis = (slotIndex * 80).coerceAtMost(400)),
                                )
                        ) togetherWith (
                            slideOutHorizontally(
                                animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                                targetOffsetX = { fullWidth -> -(fullWidth * 0.45f).toInt() },
                            ) +
                                fadeOut(
                                    animationSpec = tween(durationMillis = 700),
                                )
                        )
                    },
                    label = "RotatingCoverTransition",
                ) { currentItem ->
                    // 封面占比由 64% (max 175dp) 提升至 80% (max 240dp)
                    val singleSize = (cardWidth * 0.80f).coerceIn(160.dp, 240.dp)
                    if (currentItem.coverUrl.isNotBlank() || currentItem.albumMid.isNotBlank()) {
                        MelodistElevatedCover(
                            coverUrl = currentItem.coverUrl,
                            albumMid = currentItem.albumMid,
                            songMid = currentItem.songMid,
                            contentDescription = null,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(singleSize),
                        )
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .size((cardWidth * 0.60f).coerceIn(120.dp, 180.dp))
                                    .clip(MelodistShapes.CardCorner)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), MelodistShapes.CardCorner),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = currentItem.title.take(2).ifBlank { "音乐" },
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // 下半部：大字标题 + 小字副标题 + 悬浮播放按钮
            // 采用垂直渐变过渡层，使上方封面与底部文字无缝融合
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.verticalGradient(
                                colors =
                                    listOf(
                                        Color.Transparent,
                                        animatedCardBg.toMonetContainer(0.02f).copy(alpha = 0.82f),
                                        animatedCardBg.toMonetContainer(0.04f).copy(alpha = 0.96f),
                                    ),
                            ),
                        ).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    AnimatedContent(
                        targetState = item,
                        transitionSpec = {
                            (
                                slideInHorizontally(
                                    animationSpec = tween(durationMillis = 850, delayMillis = (slotIndex * 80).coerceAtMost(400), easing = FastOutSlowInEasing),
                                    initialOffsetX = { fullWidth -> (fullWidth * 0.35f).toInt() },
                                ) +
                                    fadeIn(
                                        animationSpec = tween(durationMillis = 750, delayMillis = (slotIndex * 80).coerceAtMost(400)),
                                    )
                            ) togetherWith (
                                slideOutHorizontally(
                                    animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
                                    targetOffsetX = { fullWidth -> -(fullWidth * 0.35f).toInt() },
                                ) +
                                    fadeOut(
                                        animationSpec = tween(durationMillis = 650),
                                    )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        label = "RotatingTextTransition",
                    ) { targetItem ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (targetItem.badgeText.isNotBlank()) {
                                Box(
                                    modifier =
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(animatedAccentContainer.copy(alpha = 0.65f))
                                            .border(0.5.dp, animatedAccentColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = targetItem.badgeText,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = animatedAccentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Text(
                                text = targetItem.title.ifBlank { "未知单曲" },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            Text(
                                text = targetItem.subtitle.ifBlank { "精选推荐" },
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (showPlayButton) {
                        Spacer(modifier = Modifier.width(10.dp))

                        Box(
                            modifier =
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(animatedAccentColor)
                                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "播放",
                                tint = animatedAccentOnColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
