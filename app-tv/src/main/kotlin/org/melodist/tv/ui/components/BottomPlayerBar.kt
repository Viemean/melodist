package org.melodist.tv.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.toMonetContainer
import java.util.Locale
import android.view.KeyEvent as AndroidKeyEvent

@Composable
fun BottomPlayerBar(
    progressMs: Long = 155000L,
    progressMsProvider: (() -> Long)? = null,
    durationMs: Long = 236000L,
    surfaceColor: Color = MelodistColors.SurfaceDark,
    isPlaying: Boolean = true,
    isFavorite: Boolean = false,
    canFavorite: Boolean = true,
    loopMode: String = "列表循环",
    qualityLabel: String = "SQ 无损",
    canChangeQuality: Boolean = true,
    queueCount: Int = 333,
    horizontalPadding: Dp = 48.dp,
    onFavoriteClick: () -> Unit = {},
    onLoopClick: () -> Unit = {},
    onPrevClick: () -> Unit = {},
    onPlayPauseClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    onQualityClick: () -> Unit = {},
    onQueueClick: () -> Unit = {},
    onFullscreenClick: () -> Unit = {},
    onDownPress: () -> Unit = {},
    onSeekBy: (Long) -> Unit = {},
    accentColor: Color = MelodistColors.AccentGreen,
    modifier: Modifier = Modifier,
) {
    val actualProgressProvider = progressMsProvider ?: { progressMs }

    val loopIcon =
        when {
            loopMode.contains("单曲") -> Icons.Filled.RepeatOne
            loopMode.contains("随机") -> Icons.Filled.Shuffle
            else -> Icons.Filled.Repeat
        }

    val progressRequester = remember { FocusRequester() }

    val favRequester = remember { FocusRequester() }
    val loopRequester = remember { FocusRequester() }
    val prevRequester = remember { FocusRequester() }
    val playPauseRequester = remember { FocusRequester() }
    val nextRequester = remember { FocusRequester() }
    val fullscreenRequester = remember { FocusRequester() }
    val qualityRequester = remember { FocusRequester() }
    val queueRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        playPauseRequester.requestFocus()
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 通栏进度条（支持遥控器上键选中，选中后左右键每次快进/快退 15 秒）
        PlayerProgressBar(
            progressMsProvider = actualProgressProvider,
            durationMs = durationMs,
            accentColor = accentColor,
            progressRequester = progressRequester,
            playPauseRequester = playPauseRequester,
            onSeekBy = onSeekBy,
        )

        // 控制按键组
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            onDownPress()
                            true
                        } else {
                            false
                        }
                    },
        ) {
            val btnContainer = surfaceColor.toMonetContainer(0.08f)

            // 左侧：收藏、循环模式
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canFavorite) {
                    TvIconButton(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFavorite) "取消收藏" else "收藏",
                        iconTint = if (isFavorite) MelodistColors.FavoriteRed else MelodistColors.TextPrimary,
                        containerBg = btnContainer,
                        modifier =
                            Modifier
                                .focusRequester(favRequester)
                                .focusProperties {
                                    up = progressRequester
                                    right = loopRequester
                                },
                        onClick = onFavoriteClick,
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                }
                TvIconButton(
                    imageVector = loopIcon,
                    contentDescription = loopMode,
                    containerBg = btnContainer,
                    modifier =
                        Modifier
                            .focusRequester(loopRequester)
                            .focusProperties {
                                up = progressRequester
                                if (canFavorite) {
                                    left = favRequester
                                }
                                right = prevRequester
                            },
                    onClick = onLoopClick,
                )
            }

            // 控制按键：上一首、播放/暂停、下一首
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvIconButton(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "上一首",
                    containerBg = btnContainer,
                    modifier =
                        Modifier
                            .focusRequester(prevRequester)
                            .focusProperties {
                                up = progressRequester
                                left = loopRequester
                                right = playPauseRequester
                            },
                    onClick = onPrevClick,
                )
                Spacer(modifier = Modifier.width(18.dp))
                TvIconButton(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    containerBg = btnContainer,
                    modifier =
                        Modifier
                            .focusRequester(playPauseRequester)
                            .focusProperties {
                                up = progressRequester
                                left = prevRequester
                                right = nextRequester
                            },
                    onClick = onPlayPauseClick,
                )
                Spacer(modifier = Modifier.width(18.dp))
                TvIconButton(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "下一首",
                    containerBg = btnContainer,
                    modifier =
                        Modifier
                            .focusRequester(nextRequester)
                            .focusProperties {
                                up = progressRequester
                                left = playPauseRequester
                                right = fullscreenRequester
                            },
                    onClick = onNextClick,
                )
            }

            // 右侧：全屏隐藏按钮、音质按钮、播放队列
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvIconButton(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = "隐藏控制栏",
                    containerBg = btnContainer,
                    modifier =
                        Modifier
                            .focusRequester(fullscreenRequester)
                            .focusProperties {
                                up = progressRequester
                                left = nextRequester
                                right = if (canChangeQuality) qualityRequester else queueRequester
                            },
                    onClick = onFullscreenClick,
                )
                Spacer(modifier = Modifier.width(14.dp))
                TvQualityButton(
                    label = qualityLabel,
                    containerBg = btnContainer,
                    canChangeQuality = canChangeQuality,
                    modifier =
                        if (canChangeQuality) {
                            Modifier
                                .focusRequester(qualityRequester)
                                .focusProperties {
                                    up = progressRequester
                                    left = fullscreenRequester
                                    right = queueRequester
                                }
                        } else {
                            Modifier
                        },
                    onClick = onQualityClick,
                )
                Spacer(modifier = Modifier.width(14.dp))
                TvQueueButton(
                    queueCount = queueCount,
                    containerBg = btnContainer,
                    modifier =
                        Modifier
                            .focusRequester(queueRequester)
                            .focusProperties {
                                up = progressRequester
                                left = if (canChangeQuality) qualityRequester else fullscreenRequester
                            },
                    onClick = onQueueClick,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MelodistColors.TextPrimary,
    isHighlight: Boolean = false,
    size: Dp = 42.dp,
    iconSize: Dp = 22.dp,
    containerBg: Color = MelodistColors.ContainerDarkSecondary,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .size(size)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { onClick() },
        interactionSource = interactionSource,
        shape =
            IconButtonDefaults.shape(
                shape = MelodistShapes.ButtonCorner,
                focusedShape = MelodistShapes.ButtonCorner,
            ),
        colors =
            IconButtonDefaults.colors(
                containerColor = if (isHighlight) MelodistColors.AccentGreen else containerBg,
                focusedContainerColor = Color.White,
                contentColor = if (isHighlight) Color.Black else iconTint,
                focusedContentColor = Color.Black,
            ),
        border =
            IconButtonDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        shape = MelodistShapes.ButtonCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.ButtonCorner,
                    ),
            ),
        scale =
            IconButtonDefaults.scale(
                focusedScale = 1.1f,
            ),
    ) {
        AnimatedContent(
            targetState = imageVector,
            transitionSpec = {
                (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.8f, animationSpec = tween(180)))
                    .togetherWith(
                        fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.8f, animationSpec = tween(140))
                    )
            },
            label = "TvIconButtonAnim",
        ) { targetIcon ->
            Icon(
                imageVector = targetIcon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvQueueButton(
    queueCount: Int,
    modifier: Modifier = Modifier,
    containerBg: Color = MelodistColors.ContainerDarkSecondary,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier =
            modifier
                .height(42.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { onClick() },
        interactionSource = interactionSource,
        shape =
            ButtonDefaults.shape(
                shape = MelodistShapes.ButtonCorner,
                focusedShape = MelodistShapes.ButtonCorner,
            ),
        colors =
            ButtonDefaults.colors(
                containerColor = containerBg,
                focusedContainerColor = Color.White,
                contentColor = MelodistColors.TextPrimary,
                focusedContentColor = Color.Black,
            ),
        border =
            ButtonDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        shape = MelodistShapes.ButtonCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.ButtonCorner,
                    ),
            ),
        scale =
            ButtonDefaults.scale(
                focusedScale = 1.06f,
            ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "播放列表",
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "$queueCount",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvQualityButton(
    label: String,
    modifier: Modifier = Modifier,
    containerBg: Color = MelodistColors.QualityGoldBg,
    canChangeQuality: Boolean = true,
    onClick: () -> Unit,
) {
    if (!canChangeQuality) {
        Box(
            modifier =
                modifier
                    .height(40.dp)
                    .background(containerBg, MelodistShapes.ButtonCorner)
                    .border(BorderStroke(1.dp, MelodistColors.QualityGoldBorder), MelodistShapes.ButtonCorner)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MelodistColors.QualityGoldText,
            )
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.height(40.dp),
            shape =
                ButtonDefaults.shape(
                    shape = MelodistShapes.ButtonCorner,
                    focusedShape = MelodistShapes.ButtonCorner,
                ),
            colors =
                ButtonDefaults.colors(
                    containerColor = containerBg,
                    focusedContainerColor = Color.White,
                    contentColor = MelodistColors.QualityGoldText,
                    focusedContentColor = Color.Black,
                ),
            border =
                ButtonDefaults.border(
                    border =
                        Border(
                            border = BorderStroke(1.dp, MelodistColors.QualityGoldBorder),
                            shape = MelodistShapes.ButtonCorner,
                        ),
                    focusedBorder =
                        Border(
                            border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                            shape = MelodistShapes.ButtonCorner,
                        ),
                ),
            scale =
                ButtonDefaults.scale(
                    focusedScale = 1.06f,
                ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

@Composable
private fun PlayerProgressBar(
    progressMsProvider: () -> Long,
    durationMs: Long,
    accentColor: Color,
    progressRequester: FocusRequester,
    playPauseRequester: FocusRequester,
    onSeekBy: (Long) -> Unit,
) {
    val progressInteractionSource = remember { MutableInteractionSource() }
    val isProgressFocused by progressInteractionSource.collectIsFocusedAsState()

    val progressMs by org.melodist.playback.PlaybackManager.currentPositionMs
        .collectAsState()
    val progressFraction =
        if (durationMs > 0) {
            (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val currentFormatted = formatMs(progressMs)
    val totalFormatted = formatMs(durationMs)

    val progressHeight = if (isProgressFocused) 8.dp else 4.dp
    val progressBorder =
        if (isProgressFocused) {
            Modifier.border(BorderStroke(2.dp, MelodistColors.FocusTeal), MelodistShapes.PillCorner)
        } else {
            Modifier
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(progressRequester)
                .focusProperties {
                    down = playPauseRequester
                }.focusable(interactionSource = progressInteractionSource)
                .onKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                onSeekBy(-15000L)
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                onSeekBy(15000L)
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }.padding(vertical = 4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(progressHeight)
                    .then(progressBorder)
                    .clip(MelodistShapes.PillCorner)
                    .background(MelodistColors.ProgressTrack),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(progressFraction)
                        .fillMaxHeight()
                        .background(if (isProgressFocused) MelodistColors.FocusTeal else accentColor),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 左右时间标签
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentFormatted,
                color = if (isProgressFocused) MelodistColors.FocusTeal else MelodistColors.TextSecondary,
                fontWeight = if (isProgressFocused) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp,
            )
            Text(
                text = totalFormatted,
                color = MelodistColors.TextSecondary,
                fontSize = 13.sp,
            )
        }
    }
}
