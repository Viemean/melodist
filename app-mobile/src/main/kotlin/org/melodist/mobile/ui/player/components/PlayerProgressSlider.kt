package org.melodist.mobile.ui.player.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.melodist.playback.PlaybackManager

/**
 * 播放器进度滑块与时间显示组件（Android 13/14 官方 M3 动态波浪 Squiggle 风格）。
 * 核心性能优化：独立在此叶子 Composable 内部订阅 [PlaybackManager.currentPositionMs]，
 * 彻底阻断 60ms 高频更新向上级父容器扩散，实现重组隔离。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val interactionSource = remember { MutableInteractionSource() }

    val durationSafe = durationMs.coerceAtLeast(1L)
    val currentPosSafe =
        if (isDraggingSlider) {
            (draggingSliderValue * durationSafe).toLong()
        } else {
            sliderPositionMs.coerceIn(0L, durationSafe)
        }
    val progressFraction = (currentPosSafe.toFloat() / durationSafe).coerceIn(0f, 1f)

    // 莫奈色彩：与主界面全局动态色彩系统保持一致
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackInactiveColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f)

    val sliderColors =
        SliderDefaults.colors(
            thumbColor = primaryColor,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
        )

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
            interactionSource = interactionSource,
            colors = sliderColors,
            thumb = {},
            track = {
                val density = LocalDensity.current
                val isPressed by interactionSource.collectIsPressedAsState()
                val isEngaged = isPressed || isDraggingSlider
                val thumbRadius by animateDpAsState(
                    targetValue = if (isEngaged) 7.5.dp else 4.dp,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    label = "thumb_radius",
                )
                val thumbRadiusPx = with(density) { thumbRadius.toPx() }
                val trackHeightPx = with(density) { 5.dp.toPx() }

                Canvas(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(24.dp),
                ) {
                    val centerY = size.height / 2f
                    val activeWidth = size.width * progressFraction

                    // 1. 未播放平滑底轨（与主界面一致的莫奈表层色）
                    drawLine(
                        color = trackInactiveColor,
                        start = Offset(0f, centerY),
                        end = Offset(size.width, centerY),
                        strokeWidth = trackHeightPx,
                        cap = StrokeCap.Round,
                    )

                    // 2. 已播放进度线（莫奈主色平滑覆盖）
                    if (activeWidth > 0f) {
                        drawLine(
                            color = primaryColor,
                            start = Offset(0f, centerY),
                            end = Offset(activeWidth, centerY),
                            strokeWidth = trackHeightPx,
                            cap = StrokeCap.Round,
                        )
                    }

                    // 3. 纯实色平滑滑块（拖拽或按压时微放大反馈，无任何外圈割裂）
                    if (isEngaged || activeWidth > 0f) {
                        drawCircle(
                            color = primaryColor,
                            radius = thumbRadiusPx,
                            center = Offset(activeWidth, centerY),
                        )
                    }
                }
            },
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
