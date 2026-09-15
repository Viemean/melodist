package org.melodist.mobile.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
    isMuted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val playPauseContainerColor = if (isDark) Color.White else Color(0xFF1C1B1F)
    val playPauseContentColor = if (isDark) Color(0xFF1C1B1F) else Color.White
    val auxButtonBgColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 循环模式按键（猜你喜欢模式下锁定为官方推荐顺序）
        IconButton(
            onClick = {
                if (!isRadioMode) onToggleLoopMode()
            },
            enabled = !isRadioMode,
            modifier =
                Modifier
                    .size(46.dp)
                    .background(
                        if (isRadioMode) auxButtonBgColor.copy(alpha = 0.04f) else auxButtonBgColor,
                        CircleShape,
                    ),
        ) {
            Icon(
                imageVector =
                    if (isRadioMode) {
                        Icons.Default.Radio
                    } else {
                        when (loopMode) {
                            PlaybackLoopMode.ListRepeat -> Icons.Default.Repeat
                            PlaybackLoopMode.SingleRepeat -> Icons.Default.RepeatOne
                            PlaybackLoopMode.Shuffle -> Icons.Default.Shuffle
                        }
                    },
                contentDescription = if (isRadioMode) "猜你喜欢模式（官方推荐顺序）" else loopMode.label,
                tint = if (isRadioMode) contentPrimary.copy(alpha = 0.38f) else contentPrimary,
                modifier = Modifier.size(24.dp),
            )
        }

        // 播放/暂停大按键（68dp 专辑主题色圆盘，带同色系柔和光晕投影）
        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier =
                Modifier
                    .size(68.dp)
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
            Icon(
                imageVector =
                    when {
                        isMuted -> Icons.Default.PlayArrow
                        isPlaying -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    },
                contentDescription =
                    if (isMuted) {
                        "播放"
                    } else if (isPlaying) {
                        "暂停"
                    } else {
                        "播放"
                    },
                modifier = Modifier.size(38.dp),
            )
        }

        // 右侧按钮：播放列表队列
        IconButton(
            onClick = {
                if (!isRadioMode) onOpenQueue()
            },
            enabled = !isRadioMode,
            modifier =
                Modifier
                    .size(46.dp)
                    .background(
                        if (isRadioMode) auxButtonBgColor.copy(alpha = 0.04f) else auxButtonBgColor,
                        CircleShape,
                    ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = if (isRadioMode) "猜你喜欢模式下播放列表不可用" else "播放队列",
                tint = if (isRadioMode) contentPrimary.copy(alpha = 0.28f) else contentPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
