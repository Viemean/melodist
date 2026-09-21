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
    val isFromCache by PlaybackManager.isCurrentTrackFromCache.collectAsState()
    val fileCacheFraction by PlaybackManager.fileCacheFraction.collectAsState()
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
    // 真实音频文件磁盘缓存比例：若曲目已完整落盘则直接 100%，否则按已下载字节呈现
    val actualCacheFraction = if (isFromCache) 1f else fileCacheFraction.coerceIn(0f, 1f)

    // 莫奈色彩：使用传入的动态莫奈强调色，确保在动态背景下具备绝佳辨识度与色彩呼应
    val effectiveAccent = accentColor
    // 未播底轨：基于高对比度文本/内容色派生，保证在亮色微彩与暗色深色背景下均轮廓鲜明
    val trackInactiveColor = textColor.copy(alpha = 0.20f)

    val sliderColors =
        SliderDefaults.colors(
            thumbColor = effectiveAccent,
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
                    targetValue = if (isEngaged) 8.dp else 5.dp,
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
                    val cachedWidth = size.width * actualCacheFraction

                    // 1. 未播放平滑底轨（高对比度半透明凹槽）
                    drawLine(
                        color = trackInactiveColor,
                        start = Offset(0f, centerY),
                        end = Offset(size.width, centerY),
                        strokeWidth = trackHeightPx,
                        cap = StrokeCap.Round,
                    )

                    // 2. 音频文件磁盘缓存进度（若文件正在下载写入，以 35% 莫奈主色实时展示已落盘范围；已全盘缓存则覆盖整轨）
                    if (cachedWidth > 0f) {
                        drawLine(
                            color = effectiveAccent.copy(alpha = 0.35f),
                            start = Offset(0f, centerY),
                            end = Offset(cachedWidth, centerY),
                            strokeWidth = trackHeightPx,
                            cap = StrokeCap.Round,
                        )
                    }

                    // 3. 已播放进度线（莫奈强调色平滑覆盖）
                    if (activeWidth > 0f) {
                        drawLine(
                            color = effectiveAccent,
                            start = Offset(0f, centerY),
                            end = Offset(activeWidth, centerY),
                            strokeWidth = trackHeightPx,
                            cap = StrokeCap.Round,
                        )
                    }

                    // 4. 纯实色平滑滑块（拖拽或按压时微放大反馈，无任何外圈割裂）
                    if (isEngaged || activeWidth > 0f) {
                        drawCircle(
                            color = effectiveAccent,
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
