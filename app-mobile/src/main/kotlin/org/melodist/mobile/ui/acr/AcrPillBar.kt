package org.melodist.mobile.ui.acr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.melodist.model.Song
import java.util.Locale

private enum class PillMode {
    Listening,
    Success,
    Empty,
}

/**
 * 灵动岛风格识别胶囊条。
 *
 * - Listening 态：识别计时（0.1s 开始精准递增）+ 固定取消按鈕
 * - Success 态：封面缩略图 + 歌名/歌手 + 关闭按鈕，点击展开播放界面
 */
@Composable
fun AcrPillBar(
    uiState: MobileAcrUiState,
    onCancel: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    showBriefSuccess: Boolean = false,
    briefSuccessText: String = "",
    modifier: Modifier = Modifier,
) {
    val pillColor by animateColorAsState(
        targetValue =
            when (uiState) {
                is MobileAcrUiState.Listening -> MaterialTheme.colorScheme.primaryContainer
                is MobileAcrUiState.Success -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
        animationSpec = tween(400),
        label = "pill_color",
    )

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = pillColor,
        modifier =
            modifier
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                // 外层统一处理点击展开/取消，内部关闭按鈕独立拦截
                .pointerInput(uiState) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        down.consume()
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                            when (uiState) {
                                is MobileAcrUiState.Success -> onExpand()
                                is MobileAcrUiState.Listening -> onCancel()
                                else -> {}
                            }
                        }
                    }
                },
    ) {
        val currentMode =
            when (uiState) {
                is MobileAcrUiState.Listening -> PillMode.Listening
                is MobileAcrUiState.Success -> PillMode.Success
                else -> PillMode.Empty
            }

        AnimatedContent(
            targetState = currentMode,
            transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(180)) },
            label = "pill_mode_content",
        ) { mode ->
            when (mode) {
                PillMode.Listening -> {
                    val elapsed = (uiState as? MobileAcrUiState.Listening)?.elapsedSeconds ?: 0f
                    ListeningPillContent(
                        elapsedSeconds = elapsed,
                        onCancel = onCancel,
                    )
                }
                PillMode.Success -> {
                    val song = (uiState as? MobileAcrUiState.Success)?.song
                    if (song != null) {
                        val isBrief = showBriefSuccess && briefSuccessText.isNotBlank()
                        AnimatedContent(
                            targetState = isBrief,
                            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
                            label = "pill_success_subcontent",
                        ) { briefMode ->
                            if (briefMode) {
                                BriefSuccessPillContent(
                                    text = briefSuccessText,
                                    onClose = onClose,
                                )
                            } else {
                                SuccessPillContent(song = song, onClose = onClose)
                            }
                        }
                    }
                }
                PillMode.Empty -> Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ListeningPillContent(
    elapsedSeconds: Float,
    onCancel: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = "正在识别",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(18.dp),
        )

        // 识别计时文本 (0.1秒开始精准计时递增，如 "0.1s", "1.2s", "4.5s")
        val formattedTime = String.format(Locale.US, "%.1fs", elapsedSeconds)
        Text(
            text = "正在识别 $formattedTime",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
        )

        // 固定的取消识别按鈕（独立事件拦截，不再参与高频动画闪烁）
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                onCancel()
                            }
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "取消识别",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun SuccessPillContent(
    song: Song,
    onClose: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 封面缩略图
        val coverUrl = song.thumbnailCoverUrl.ifBlank { song.coverUrl }
        if (coverUrl.isNotBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp)),
            )
        }

        // 歌名 + 歌手
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
            )
            if (song.singer.isNotBlank()) {
                Text(
                    text = " · ${song.singer}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )
            }
        }

        // 关闭按钮（单独可点击区域，由外层 pointerInput 检测 close 区域点击）
        // 注意：close 按钮区域通过独立的 pointerInput 处理，不嵌套 clickable
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    // 独立的 pointerInput 处理关闭，不与父级 pointerInput 冲突
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                onClose()
                            }
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "关闭识别结果",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun BriefSuccessPillContent(
    text: String,
    onClose: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
        }

        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )

        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                onClose()
                            }
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "关闭识别结果",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
