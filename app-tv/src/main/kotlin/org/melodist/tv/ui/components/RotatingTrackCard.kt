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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.MonetColorExtractor

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

    // 单图 Monet 取色
    var dynamicBgColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(item.coverUrl) {
        if (item.coverUrl.isNotBlank()) {
            dynamicBgColor = MonetColorExtractor.extractFromUrl(item.coverUrl)
        }
    }

    val currentBgColor = dynamicBgColor ?: item.defaultBgColor
    val animatedBgColor by animateColorAsState(
        targetValue = currentBgColor,
        animationSpec = tween(600),
        label = "RotatingCardBgColor",
    )

    // 基于卡片当前莫奈色推导同色系明亮活泼的按钮色彩（高亮背景 + 深色前景）
    val playButtonBgColor =
        remember(currentBgColor) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(currentBgColor.toArgb(), hsv)
            // 保持原图主色相，提升明度至 0.95f，适度饱和度 0.35f 呈现清爽通透质感
            hsv[1] = (hsv[1] * 0.85f).coerceIn(0.28f, 0.45f)
            hsv[2] = 0.95f
            Color(android.graphics.Color.HSVToColor(hsv))
        }
    val animatedPlayButtonBg by animateColorAsState(
        targetValue = playButtonBgColor,
        animationSpec = tween(600),
        label = "PlayButtonBgColor",
    )

    val playButtonIconColor =
        remember(currentBgColor) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(currentBgColor.toArgb(), hsv)
            // 深色图标前景色保证充足对比度
            hsv[1] = (hsv[1] * 1.15f).coerceIn(0.60f, 0.95f)
            hsv[2] = 0.20f
            Color(android.graphics.Color.HSVToColor(hsv))
        }
    val animatedPlayButtonIcon by animateColorAsState(
        targetValue = playButtonIconColor,
        animationSpec = tween(600),
        label = "PlayButtonIconColor",
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
                containerColor = animatedBgColor.copy(alpha = 0.90f),
                focusedContainerColor = animatedBgColor,
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
                    .background(animatedBgColor),
        ) {
            // 上半部：专辑封面展示（平滑滑出滑进）
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(cardHeight * 0.65f)
                        .padding(top = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = item,
                    transitionSpec = {
                        (slideInHorizontally(
                            animationSpec = tween(durationMillis = 900, delayMillis = (slotIndex * 80).coerceAtMost(400), easing = FastOutSlowInEasing),
                            initialOffsetX = { fullWidth -> (fullWidth * 0.45f).toInt() },
                        ) + fadeIn(
                            animationSpec = tween(durationMillis = 800, delayMillis = (slotIndex * 80).coerceAtMost(400)),
                        )) togetherWith (slideOutHorizontally(
                            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                            targetOffsetX = { fullWidth -> -(fullWidth * 0.45f).toInt() },
                        ) + fadeOut(
                            animationSpec = tween(durationMillis = 700),
                        ))
                    },
                    label = "RotatingCoverTransition",
                ) { currentItem ->
                    val singleSize = (cardWidth * 0.64f).coerceIn(130.dp, 175.dp)
                    if (currentItem.coverUrl.isNotBlank() || currentItem.albumMid.isNotBlank()) {
                        MelodistElevatedCover(
                            coverUrl = currentItem.coverUrl,
                            albumMid = currentItem.albumMid,
                            songMid = currentItem.songMid,
                            contentDescription = null,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.size(singleSize),
                        )
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .size((cardWidth * 0.50f).coerceIn(100.dp, 130.dp))
                                    .clip(MelodistShapes.CardCorner)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), MelodistShapes.CardCorner),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = currentItem.title.take(2).ifBlank { "音乐" },
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // 下半部：大字标题 + 小字副标题 + 悬浮播放按钮
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(Color(0xFF1A2234).copy(alpha = 0.50f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    AnimatedContent(
                        targetState = item,
                        transitionSpec = {
                            (slideInHorizontally(
                                animationSpec = tween(durationMillis = 850, delayMillis = (slotIndex * 80).coerceAtMost(400), easing = FastOutSlowInEasing),
                                initialOffsetX = { fullWidth -> (fullWidth * 0.35f).toInt() },
                            ) + fadeIn(
                                animationSpec = tween(durationMillis = 750, delayMillis = (slotIndex * 80).coerceAtMost(400)),
                            )) togetherWith (slideOutHorizontally(
                                animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
                                targetOffsetX = { fullWidth -> -(fullWidth * 0.35f).toInt() },
                            ) + fadeOut(
                                animationSpec = tween(durationMillis = 650),
                            ))
                        },
                        modifier = Modifier.weight(1f),
                        label = "RotatingTextTransition",
                    ) { targetItem ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (targetItem.badgeText.isNotBlank()) {
                                Text(
                                    text = targetItem.badgeText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFB4F8FF),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
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
                                    .background(animatedPlayButtonBg)
                                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "播放",
                                tint = animatedPlayButtonIcon,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
