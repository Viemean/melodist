package org.melodist.mobile.ui.acr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.melodist.mobile.ui.player.LocalTopSheetDragController
import org.melodist.model.Song
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 听歌识曲识别成功后弹出的卡片。
 * 长度与顶栏识别胶囊对齐（两侧 16.dp 外边距），展示封面、曲目信息与右侧快速播放按钮。
 * 支持下滑拉出全屏播放界面；上滑或左右轻扫提前塞回胶囊；取消卡片点击跳转。
 */
@Composable
fun AcrResultCard(
    song: Song,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onCollapse: () -> Unit = {},
    onPrepareSong: (() -> Unit)? = null,
    onInteractionStateChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    val topSheetDragController = LocalTopSheetDragController.current
    val velocityTracker = remember { VelocityTracker() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val cardOffsetX = remember { Animatable(0f) }
    var isHorizontalDrag by remember { mutableStateOf<Boolean?>(null) }
    var totalDragX by remember { mutableFloatStateOf(0f) }
    var totalDragY by remember { mutableFloatStateOf(0f) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .offset { IntOffset(cardOffsetX.value.roundToInt(), 0) }
                .graphicsLayer {
                    val progress = (abs(cardOffsetX.value) / with(density) { 160.dp.toPx() }).coerceIn(0f, 1f)
                    alpha = 1f - progress * 0.5f
                }.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val isPressed = event.changes.any { it.pressed }
                            onInteractionStateChange?.invoke(isPressed)
                        }
                    }
                }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isHorizontalDrag = null
                            totalDragX = 0f
                            totalDragY = 0f
                            onInteractionStateChange?.invoke(true)
                            velocityTracker.resetTracking()
                        },
                        onDrag = { change, dragAmount ->
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            totalDragX += dragAmount.x
                            totalDragY += dragAmount.y

                            // 初始 6dp 判定主方向锁定
                            if (isHorizontalDrag == null) {
                                val absX = abs(totalDragX)
                                val absY = abs(totalDragY)
                                val lockThreshold = with(density) { 6.dp.toPx() }
                                if (absX > lockThreshold || absY > lockThreshold) {
                                    if (absX > absY) {
                                        isHorizontalDrag = true
                                    } else {
                                        isHorizontalDrag = false
                                        if (totalDragY > 0f) {
                                            onPrepareSong?.invoke()
                                            topSheetDragController?.onDragStart?.invoke()
                                        }
                                    }
                                }
                            }

                            when (isHorizontalDrag) {
                                true -> {
                                    // 左右滑动：实时位移跟手反馈
                                    scope.launch {
                                        cardOffsetX.snapTo(cardOffsetX.value + dragAmount.x)
                                    }
                                }
                                false -> {
                                    if (totalDragY >= 0f) {
                                        // 下滑拉出全屏播放器
                                        topSheetDragController?.onDrag?.invoke(dragAmount.y)
                                    }
                                }
                                null -> {}
                            }
                        },
                        onDragEnd = {
                            onInteractionStateChange?.invoke(false)
                            val velocity = velocityTracker.calculateVelocity()
                            val velocityX = velocity.x
                            val velocityY = velocity.y
                            val dismissThresholdX = with(density) { 48.dp.toPx() }
                            val dismissThresholdY = with(density) { 36.dp.toPx() }

                            when (isHorizontalDrag) {
                                true -> {
                                    // 左右滑动：超过阈值或甩动则顺势滑出并提前塞回胶囊
                                    val shouldDismiss = abs(cardOffsetX.value) > dismissThresholdX || abs(velocityX) > 450f
                                    if (shouldDismiss) {
                                        scope.launch {
                                            val sign = if (cardOffsetX.value != 0f) kotlin.math.sign(cardOffsetX.value) else kotlin.math.sign(velocityX).coerceAtLeast(1f)
                                            val targetX = cardOffsetX.value + sign * with(density) { 260.dp.toPx() }
                                            cardOffsetX.animateTo(targetX, tween(160, easing = FastOutSlowInEasing))
                                            onCollapse()
                                            cardOffsetX.snapTo(0f)
                                        }
                                    } else {
                                        scope.launch {
                                            cardOffsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                        }
                                    }
                                }
                                false -> {
                                    // 仅向下滑动时驱动 topSheet 展开判定；取消卡片本身上滑强制收起
                                    if (totalDragY > 0f) {
                                        topSheetDragController?.onDragEnd?.invoke(velocityY, onCollapse)
                                    } else {
                                        topSheetDragController?.onDragCancel?.invoke(onCollapse)
                                    }
                                }
                                null -> {
                                    // 未锁定方向时仅支持左右快扫塞回
                                    if (abs(velocityX) > 450f) {
                                        onCollapse()
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            onInteractionStateChange?.invoke(false)
                            scope.launch {
                                cardOffsetX.snapTo(0f)
                            }
                            topSheetDragController?.onDragCancel?.invoke(onCollapse)
                        },
                    )
                }
                .clip(RoundedCornerShape(16.dp)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors =
                                listOf(
                                    primaryContainer.copy(alpha = 0.35f),
                                    Color.Transparent,
                                ),
                        ),
                    ).padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧封面
                val coverUrl = song.thumbnailCoverUrl.ifBlank { song.coverUrl }
                Box(
                    modifier =
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = song.name,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 中间歌曲信息
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = primaryColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = "识别成功",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "下滑播放",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = song.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    val artistAlbum =
                        listOf(song.singer, song.album)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    Text(
                        text = artistAlbum.ifBlank { "未知曲目信息" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 右侧播放按钮
                FilledTonalIconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.size(42.dp),
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
