package org.melodist.mobile.ui.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.melodist.playback.PlaybackManager

/**
 * 播放器进度滑块与时间显示组件。
 * 核心性能优化：独立在此叶子 Composable 内部订阅 [PlaybackManager.currentPositionMs]，
 * 彻底阻断 60ms 高频更新向上级父容器扩散，实现重组隔离。
 */
@Composable
fun PlayerProgressSlider(
    durationMs: Long,
    accentColor: Color,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val sliderPositionMs by PlaybackManager.currentPositionMs.collectAsState()
    var isDraggingSlider by remember { mutableStateOf(false) }
    var draggingSliderValue by remember { mutableFloatStateOf(0f) }

    val durationSafe = durationMs.coerceAtLeast(1L)
    val currentPosSafe =
        if (isDraggingSlider) {
            (draggingSliderValue * durationSafe).toLong()
        } else {
            sliderPositionMs.coerceIn(0L, durationSafe)
        }
    val progressFraction = (currentPosSafe.toFloat() / durationSafe).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = progressFraction,
            onValueChange = {
                isDraggingSlider = true
                draggingSliderValue = it
            },
            onValueChangeFinished = {
                isDraggingSlider = false
                val targetMs = (draggingSliderValue * durationSafe).toLong()
                onSeekTo(targetMs)
            },
            colors =
                SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = accentColor.copy(alpha = 0.20f),
                ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPlayerTime(currentPosSafe),
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
            )
            Text(
                text = formatPlayerTime(durationSafe),
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
            )
        }
    }
}

fun formatPlayerTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
