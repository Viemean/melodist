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
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
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
    val isDark = isSystemInDarkTheme()
    val playPauseContainerColor = if (isDark) Color.White else Color(0xFF1C1B1F)
    val playPauseContentColor = if (isDark) Color(0xFF1C1B1F) else Color.White
    val auxButtonBgColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    val loopInteractionSource = remember { MutableInteractionSource() }
    val isLoopPressed by loopInteractionSource.collectIsPressedAsState()
    val loopScale by animateFloatAsState(
        targetValue = if (isLoopPressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "loop_scale",
    )

    val playPauseInteractionSource = remember { MutableInteractionSource() }
    val isPlayPausePressed by playPauseInteractionSource.collectIsPressedAsState()
    val playPauseScale by animateFloatAsState(
        targetValue = if (isPlayPausePressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "play_pause_scale",
    )

    val queueInteractionSource = remember { MutableInteractionSource() }
    val isQueuePressed by queueInteractionSource.collectIsPressedAsState()
    val queueScale by animateFloatAsState(
        targetValue = if (isQueuePressed) 0.86f else 1f,
        animationSpec = spring(
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
        IconButton(
            onClick = {
                if (isRadioMode) {
                    onDislikeClick()
                } else {
                    onToggleLoopMode()
                }
            },
            enabled = true,
            interactionSource = loopInteractionSource,
            modifier =
                Modifier
                    .size(46.dp)
                    .graphicsLayer {
                        scaleX = loopScale
                        scaleY = loopScale
                    }
                    .background(
                        auxButtonBgColor,
                        CircleShape,
                    ),
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
                            Icons.Default.HeartBroken
                        } else {
                            when (mode) {
                                PlaybackLoopMode.ListRepeat -> Icons.Default.Repeat
                                PlaybackLoopMode.SingleRepeat -> Icons.Default.RepeatOne
                                PlaybackLoopMode.Shuffle -> Icons.Default.Shuffle
                            }
                        },
                    contentDescription = if (isRadioMode) "不喜欢" else loopMode.label,
                    tint = contentPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // 播放/暂停大按键（68dp 专辑主题色圆盘，带弹性物理按压与形态切换动画）
        FilledIconButton(
            onClick = onTogglePlayPause,
            interactionSource = playPauseInteractionSource,
            modifier =
                Modifier
                    .size(68.dp)
                    .graphicsLayer {
                        scaleX = playPauseScale
                        scaleY = playPauseScale
                    }
                    .shadow(
                        elevation = 4.dp,
                        shape = CircleShape,
                        spotColor = Color.Black.copy(alpha = if (isDark) 0.35f else 0.14f),
                        ambientColor = Color.Black.copy(alpha = 0.08f),
                        clip = false,
                    ),
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
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    modifier = Modifier.size(38.dp),
                )
            }
        }

        // 右侧按钮：播放列表队列（带按压微缩放弹性动画）
        IconButton(
            onClick = onOpenQueue,
            enabled = true,
            interactionSource = queueInteractionSource,
            modifier =
                Modifier
                    .size(46.dp)
                    .graphicsLayer {
                        scaleX = queueScale
                        scaleY = queueScale
                    }
                    .background(
                        auxButtonBgColor,
                        CircleShape,
                    ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = if (isRadioMode) "猜你喜欢队列" else "播放队列",
                tint = contentPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
