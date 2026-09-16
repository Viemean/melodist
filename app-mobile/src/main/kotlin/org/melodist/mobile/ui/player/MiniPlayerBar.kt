package org.melodist.mobile.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import org.melodist.core.connect.client.MobileConnectionState
import org.melodist.mobile.connect.MobileConnectManager
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

private enum class MiniPlayerDragDirection {
    NONE,
    HORIZONTAL,
    VERTICAL,
}

@Composable
fun MiniPlayerBar(
    song: Song?,
    isPlaying: Boolean,
    progressFraction: () -> Float,
    onTogglePlayPause: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayNext: () -> Unit = { PlaybackManager.playNext() },
    onPlayPrevious: () -> Unit = { PlaybackManager.playPrevious() },
    prevSong: Song? = null,
    nextSong: Song? = null,
    onDragStart: (() -> Unit)? = null,
    onDragUp: ((Float) -> Unit)? = null,
    onDragUpEnd: ((Float) -> Unit)? = null,
    onDragUpCancel: (() -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val dragOffsetX = remember { Animatable(0f) }
    var isDraggingHorizontal by remember { mutableStateOf(false) }
    val connectState by MobileConnectManager.connectionState.collectAsState()

    val currentOnPlayNext by rememberUpdatedState(onPlayNext)
    val currentOnPlayPrevious by rememberUpdatedState(onPlayPrevious)
    val currentPrevSong by rememberUpdatedState(prevSong ?: PlaybackManager.getPreviousSong())
    val currentNextSong by rememberUpdatedState(nextSong ?: PlaybackManager.getNextSong())

    val playPauseInteractionSource = remember { MutableInteractionSource() }
    val isPlayPausePressed by playPauseInteractionSource.collectIsPressedAsState()
    val playPauseScale by animateFloatAsState(
        targetValue = if (isPlayPausePressed) 0.88f else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "MiniPlayPauseScale",
    )

    // 播放曲目切换后归位
    LaunchedEffect(song?.songMid) {
        if (!isDraggingHorizontal) {
            dragOffsetX.snapTo(0f)
        }
    }

    val density = LocalDensity.current
    val minFlingDistancePx = with(density) { 32.dp.toPx() }
    val flingVelocityThreshold = 750f

    var dragDirection by remember { mutableStateOf(MiniPlayerDragDirection.NONE) }
    var accumulatedX by remember { mutableFloatStateOf(0f) }
    var accumulatedY by remember { mutableFloatStateOf(0f) }
    val velocityTracker = remember { VelocityTracker() }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .height(58.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            velocityTracker.resetTracking()
                            dragDirection = MiniPlayerDragDirection.NONE
                            accumulatedX = 0f
                            accumulatedY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            accumulatedX += dragAmount.x
                            accumulatedY += dragAmount.y

                            if (dragDirection == MiniPlayerDragDirection.NONE) {
                                val absX = kotlin.math.abs(accumulatedX)
                                val absY = kotlin.math.abs(accumulatedY)
                                if (absX > 4f || absY > 4f) {
                                    if (absX > absY) {
                                        dragDirection = MiniPlayerDragDirection.HORIZONTAL
                                        isDraggingHorizontal = true
                                    } else {
                                        dragDirection = MiniPlayerDragDirection.VERTICAL
                                        onDragStart?.invoke()
                                    }
                                }
                            }

                            when (dragDirection) {
                                MiniPlayerDragDirection.HORIZONTAL -> {
                                    change.consume()
                                    val isBlocked =
                                        (dragOffsetX.value + dragAmount.x > 0 && currentPrevSong == null) ||
                                            (dragOffsetX.value + dragAmount.x < 0 && currentNextSong == null)
                                    val factor = if (isBlocked) 0.25f else 1.0f
                                    val newOffset = dragOffsetX.value + dragAmount.x * factor
                                    val fullStepPx = with(density) { 280.dp.toPx() }
                                    MobileConnectManager.sendGestureSwipe(
                                        state = org.melodist.core.connect.model.GestureSwipeState.DRAGGING,
                                        fraction = (newOffset / fullStepPx).coerceIn(-1f, 1f),
                                    )
                                    coroutineScope.launch {
                                        dragOffsetX.snapTo(newOffset)
                                    }
                                }
                                MiniPlayerDragDirection.VERTICAL -> {
                                    if (dragAmount.y < 0f || accumulatedY < 0f) {
                                        change.consume()
                                        onDragUp?.invoke(dragAmount.y)
                                    }
                                }
                                MiniPlayerDragDirection.NONE -> {}
                            }
                        },
                        onDragEnd = {
                            if (dragDirection == MiniPlayerDragDirection.HORIZONTAL) {
                                isDraggingHorizontal = false
                                val currentOffset = dragOffsetX.value
                                val velocityX = velocityTracker.calculateVelocity().x
                                val fullStepPx = with(density) { 280.dp.toPx() }
                                val dragThresholdPx = fullStepPx * 0.38f

                                val shouldPlayNext =
                                    currentNextSong != null &&
                                        (
                                            (velocityX < -flingVelocityThreshold && currentOffset < -minFlingDistancePx) ||
                                                (currentOffset <= -dragThresholdPx)
                                        )
                                val shouldPlayPrevious =
                                    currentPrevSong != null &&
                                        (
                                            (velocityX > flingVelocityThreshold && currentOffset > minFlingDistancePx) ||
                                                (currentOffset >= dragThresholdPx)
                                        )

                                coroutineScope.launch {
                                    if (shouldPlayNext) {
                                        MobileConnectManager.sendGestureSwipe(
                                            state = org.melodist.core.connect.model.GestureSwipeState.SETTLING,
                                            fraction = (currentOffset / fullStepPx).coerceIn(-1f, 1f),
                                            targetFraction = -1f,
                                            durationMs = 180L,
                                        )
                                        dragOffsetX.animateTo(
                                            targetValue = -fullStepPx,
                                            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                                        )
                                        currentOnPlayNext()
                                        dragOffsetX.snapTo(0f)
                                    } else if (shouldPlayPrevious) {
                                        MobileConnectManager.sendGestureSwipe(
                                            state = org.melodist.core.connect.model.GestureSwipeState.SETTLING,
                                            fraction = (currentOffset / fullStepPx).coerceIn(-1f, 1f),
                                            targetFraction = 1f,
                                            durationMs = 180L,
                                        )
                                        dragOffsetX.animateTo(
                                            targetValue = fullStepPx,
                                            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                                        )
                                        currentOnPlayPrevious()
                                        dragOffsetX.snapTo(0f)
                                    } else {
                                        MobileConnectManager.sendGestureSwipe(
                                            state = org.melodist.core.connect.model.GestureSwipeState.CANCEL,
                                            fraction = (currentOffset / fullStepPx).coerceIn(-1f, 1f),
                                            targetFraction = 0f,
                                            durationMs = 200L,
                                        )
                                        dragOffsetX.animateTo(
                                            targetValue = 0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                        )
                                    }
                                }
                            } else if (dragDirection == MiniPlayerDragDirection.VERTICAL) {
                                val velocityY = velocityTracker.calculateVelocity().y
                                if (onDragUpEnd != null) {
                                    onDragUpEnd(velocityY)
                                } else if (accumulatedY < -60f || velocityY < -600f) {
                                    onClick()
                                }
                            }
                            dragDirection = MiniPlayerDragDirection.NONE
                            accumulatedX = 0f
                            accumulatedY = 0f
                        },
                        onDragCancel = {
                            if (dragDirection == MiniPlayerDragDirection.HORIZONTAL) {
                                isDraggingHorizontal = false
                                val fullStepPx = with(density) { 280.dp.toPx() }
                                MobileConnectManager.sendGestureSwipe(
                                    state = org.melodist.core.connect.model.GestureSwipeState.CANCEL,
                                    fraction = (dragOffsetX.value / fullStepPx).coerceIn(-1f, 1f),
                                    targetFraction = 0f,
                                    durationMs = 200L,
                                )
                                coroutineScope.launch {
                                    dragOffsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec =
                                            spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                    )
                                }
                            } else if (dragDirection == MiniPlayerDragDirection.VERTICAL) {
                                onDragUpCancel?.invoke()
                            }
                            dragDirection = MiniPlayerDragDirection.NONE
                            accumulatedX = 0f
                            accumulatedY = 0f
                        },
                    )
                }.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        tonalElevation = 4.dp,
        shadowElevation = 3.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(start = 10.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧可横滑切歌的歌曲内容展示区
                BoxWithConstraints(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clipToBounds(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val fullStepPx = with(density) { maxWidth.toPx() + 16.dp.toPx() }
                    val offsetVal = dragOffsetX.value

                    // 滑动切歌时跟随预览：上一首（向右滑露头）
                    if (offsetVal > 0f && currentPrevSong != null) {
                        MiniPlayerSongContent(
                            song = currentPrevSong,
                            modifier =
                                Modifier.graphicsLayer {
                                    translationX = offsetVal - fullStepPx
                                    val progress = (offsetVal / fullStepPx).coerceIn(0f, 1f)
                                    alpha = progress * 0.9f
                                },
                        )
                    }

                    // 滑动切歌时跟随预览：下一首（向左滑露头）
                    if (offsetVal < 0f && currentNextSong != null) {
                        MiniPlayerSongContent(
                            song = currentNextSong,
                            modifier =
                                Modifier.graphicsLayer {
                                    translationX = offsetVal + fullStepPx
                                    val progress = (-offsetVal / fullStepPx).coerceIn(0f, 1f)
                                    alpha = progress * 0.9f
                                },
                        )
                    }

                    // 当前歌曲内容
                    MiniPlayerSongContent(
                        song = song,
                        modifier =
                            Modifier.graphicsLayer {
                                translationX = offsetVal
                                val progress = (kotlin.math.abs(offsetVal) / fullStepPx).coerceIn(0f, 1f)
                                alpha = (1f - progress * 0.45f).coerceIn(0.2f, 1f)
                            },
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                if (connectState is MobileConnectionState.Paired) {
                    val localCtx = androidx.compose.ui.platform.LocalContext.current
                    val isTakeover = MobileConnectManager.remoteControlMode.collectAsState().value == org.melodist.core.connect.model.RemoteControlMode.TAKEOVER
                    androidx.compose.material3.IconButton(
                        onClick = {
                            if (isTakeover) {
                                android.widget.Toast.makeText(localCtx, "全面接管模式生效中：播放直通 TV", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                MobileConnectManager.relayCurrentPlaybackToTv()
                                android.widget.Toast.makeText(localCtx, "正在接力至 TV 播放", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Computer,
                            contentDescription = "已连接电脑/电视",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // M3 风格实心播放/暂停按钮 (40dp 圆形容器，深浅主题自适应 Primary 色)
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    interactionSource = playPauseInteractionSource,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .graphicsLayer {
                                scaleX = playPauseScale
                                scaleY = playPauseScale
                            },
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.75f, animationSpec = tween(180)))
                                .togetherWith(
                                    fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.75f, animationSpec = tween(140))
                                )
                        },
                        label = "MiniPlayPauseIconAnim",
                    ) { targetPlaying ->
                        Icon(
                            imageVector = if (targetPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (targetPlaying) "暂停" else "播放",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            // 底部 2dp 局部细进度条
            LinearProgressIndicator(
                progress = progressFraction,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun MiniPlayerSongContent(
    song: Song?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtImage(
            coverUrl = song?.thumbnailCoverUrl,
            contentDescription = song?.name,
            shape = RoundedCornerShape(8.dp),
            elevation = 3.dp,
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
            placeholderIconSize = 22.dp,
            modifier = Modifier.size(42.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = song?.name ?: "未在播放",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song?.singer ?: "Melodist",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
