package org.melodist.mobile.ui.player.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import org.melodist.mobile.ui.theme.isAppInDarkTheme
import org.melodist.playback.PlaybackLoopMode

@Composable
fun PlayerControlBar(
    isPlaying: Boolean,
    loopMode: PlaybackLoopMode,
    isRadioMode: Boolean,
    animatedAccentColor: Color,
    contentPrimary: Color,
    onTogglePlayPause: () -> Unit,
    onToggleLoopMode: () -> Unit,
    onOpenQueue: () -> Unit,
    onDislikeClick: () -> Unit = {},
    isMuted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isDark = isAppInDarkTheme()

    // 播放/暂停按键采用与进度条高亮完全一致的莫奈强调色，强化界面色彩统一性
    val playPauseContainerColor = animatedAccentColor
    val playPauseContentColor =
        if (ColorUtils.calculateLuminance(animatedAccentColor.toArgb()) < 0.45) {
            Color.White
        } else {
            Color(0xFF1C1B1F)
        }

    val auxButtonShape = RoundedCornerShape(16.dp)
    val auxButtonBgColor =
        if (isDark) {
            Color.White.copy(alpha = 0.06f)
        } else {
            Color.Black.copy(alpha = 0.04f)
        }

    val loopInteractionSource = remember { MutableInteractionSource() }
    val isLoopPressed by loopInteractionSource.collectIsPressedAsState()
    val loopScale by animateFloatAsState(
        targetValue = if (isLoopPressed) 0.86f else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "loop_scale",
    )

    val playPauseInteractionSource = remember { MutableInteractionSource() }
    val isPlayPausePressed by playPauseInteractionSource.collectIsPressedAsState()
    val playPauseScale by animateFloatAsState(
        targetValue = if (isPlayPausePressed) 0.88f else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "play_pause_scale",
    )

    val queueInteractionSource = remember { MutableInteractionSource() }
    val isQueuePressed by queueInteractionSource.collectIsPressedAsState()
    val queueScale by animateFloatAsState(
        targetValue = if (isQueuePressed) 0.86f else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "queue_scale",
    )

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 左侧按键：普通模式为循环模式切换，猜你喜欢电台模式为“不喜欢”按键
        FilledTonalIconButton(
            onClick = {
                if (isRadioMode) {
                    onDislikeClick()
                } else {
                    onToggleLoopMode()
                }
            },
            enabled = true,
            interactionSource = loopInteractionSource,
            shape = auxButtonShape,
            colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = auxButtonBgColor,
                    contentColor = contentPrimary,
                ),
            modifier =
                Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = loopScale
                        scaleY = loopScale
                    },
        ) {
            AnimatedContent(
                targetState = if (isRadioMode) null else loopMode,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.78f))
                        .togetherWith(fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.78f))
                },
                label = "LoopModeIconTransition",
            ) { mode ->
                Icon(
                    imageVector =
                        if (mode == null) {
                            Icons.Rounded.HeartBroken
                        } else {
                            when (mode) {
                                PlaybackLoopMode.ListRepeat -> Icons.Rounded.Repeat
                                PlaybackLoopMode.SingleRepeat -> Icons.Rounded.RepeatOne
                                PlaybackLoopMode.Shuffle -> Icons.Rounded.Shuffle
                            }
                        },
                    contentDescription = if (isRadioMode) "不喜欢" else loopMode.label,
                    tint = contentPrimary.copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // 播放/暂停大按键（68dp M3 标志性 Squircle 圆角方块，带弹性物理按压与形态切换动画）
        FilledIconButton(
            onClick = onTogglePlayPause,
            interactionSource = playPauseInteractionSource,
            modifier =
                Modifier
                    .size(68.dp)
                    .graphicsLayer {
                        scaleX = playPauseScale
                        scaleY = playPauseScale
                    }.shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(24.dp),
                        spotColor = animatedAccentColor.copy(alpha = if (isDark) 0.35f else 0.20f),
                        ambientColor = animatedAccentColor.copy(alpha = 0.08f),
                        clip = false,
                    ),
            shape = RoundedCornerShape(24.dp),
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = playPauseContainerColor,
                    contentColor = playPauseContentColor,
                ),
        ) {
            AnimatedContent(
                targetState = isPlaying && !isMuted,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.75f))
                        .togetherWith(fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.75f))
                },
                label = "PlayPauseIconTransition",
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    modifier = Modifier.size(38.dp),
                )
            }
        }

        // 右侧按钮：播放列表队列（带按压微缩放弹性动画）
        FilledTonalIconButton(
            onClick = onOpenQueue,
            enabled = true,
            interactionSource = queueInteractionSource,
            shape = auxButtonShape,
            colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = auxButtonBgColor,
                    contentColor = contentPrimary.copy(alpha = 0.85f),
                ),
            modifier =
                Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = queueScale
                        scaleY = queueScale
                    },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = if (isRadioMode) "猜你喜欢队列" else "播放队列",
                tint = contentPrimary.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
