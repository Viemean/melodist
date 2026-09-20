package org.melodist.mobile.ui.player.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.util.MobileCoverCacheResolver
import org.melodist.model.Song
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerCoverCarousel(
    currentSong: Song?,
    prevSong: Song?,
    nextSong: Song?,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val dragOffsetX = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val currentPrevSong by rememberUpdatedState(prevSong)
    val currentNextSong by rememberUpdatedState(nextSong)
    val currentOnPlayNext by rememberUpdatedState(onPlayNext)
    val currentOnPlayPrevious by rememberUpdatedState(onPlayPrevious)

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val coverWidthDp = maxWidth * 0.98f
        val coverWidthPx = with(density) { coverWidthDp.toPx() }
        val spacingPx = with(density) { 24.dp.toPx() }
        val fullStepPx = coverWidthPx + spacingPx

        val dragThresholdPx = coverWidthPx * 0.45f
        val minFlingDistancePx = with(density) { 72.dp.toPx() }
        val flingVelocityThreshold = 1000f

        val velocityTracker = remember { VelocityTracker() }
        var totalDragAccumulator by remember { mutableFloatStateOf(0f) }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (!isDragging && abs(dragOffsetX.value) < 10f) {
                                onClick()
                            }
                        },
                        onLongClick = {
                            if (!isDragging && abs(dragOffsetX.value) < 10f) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongClick?.invoke()
                            }
                        },
                    ).pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                isDragging = true
                                velocityTracker.resetTracking()
                                totalDragAccumulator = 0f
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                totalDragAccumulator += dragAmount
                                val isBlocked =
                                    (totalDragAccumulator > 0 && currentPrevSong == null) ||
                                        (totalDragAccumulator < 0 && currentNextSong == null)
                                val factor = if (isBlocked) 0.25f else 1.0f
                                val newOffset = dragOffsetX.value + dragAmount * factor
                                org.melodist.mobile.connect.MobileConnectManager.sendGestureSwipe(
                                    state = org.melodist.core.connect.model.GestureSwipeState.DRAGGING,
                                    fraction = (newOffset / fullStepPx).coerceIn(-1f, 1f),
                                )
                                coroutineScope.launch {
                                    dragOffsetX.snapTo(newOffset)
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                                val currentOffset = dragOffsetX.value
                                val velocityX = velocityTracker.calculateVelocity().x

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
                                        org.melodist.mobile.connect.MobileConnectManager.sendGestureSwipe(
                                            state = org.melodist.core.connect.model.GestureSwipeState.SETTLING,
                                            fraction = (currentOffset / fullStepPx).coerceIn(-1f, 1f),
                                            targetFraction = -1f,
                                            durationMs = 180L,
                                        )
                                        dragOffsetX.animateTo(
                                            targetValue = -fullStepPx,
                                            animationSpec = tween(durationMillis = 180, easing = LinearEasing),
                                        )
                                        currentOnPlayNext()
                                        dragOffsetX.snapTo(0f)
                                    } else if (shouldPlayPrevious) {
                                        org.melodist.mobile.connect.MobileConnectManager.sendGestureSwipe(
                                            state = org.melodist.core.connect.model.GestureSwipeState.SETTLING,
                                            fraction = (currentOffset / fullStepPx).coerceIn(-1f, 1f),
                                            targetFraction = 1f,
                                            durationMs = 180L,
                                        )
                                        dragOffsetX.animateTo(
                                            targetValue = fullStepPx,
                                            animationSpec = tween(durationMillis = 180, easing = LinearEasing),
                                        )
                                        currentOnPlayPrevious()
                                        dragOffsetX.snapTo(0f)
                                    } else {
                                        org.melodist.mobile.connect.MobileConnectManager.sendGestureSwipe(
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
                            },
                            onDragCancel = {
                                isDragging = false
                                org.melodist.mobile.connect.MobileConnectManager.sendGestureSwipe(
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
                            },
                        )
                    },
            contentAlignment = Alignment.Center,
        ) {
            val offsetVal = dragOffsetX.value

            if (currentPrevSong != null && offsetVal > 0f) {
                val progress = (offsetVal / fullStepPx).coerceIn(0f, 1f)
                val prevX = -fullStepPx + offsetVal
                CoverCard(
                    song = currentPrevSong,
                    coverWidthDp = coverWidthDp,
                    translationX = prevX,
                    scale = 0.88f + 0.12f * progress,
                    alpha = 0.40f + 0.60f * progress,
                )
            }

            if (currentNextSong != null && offsetVal < 0f) {
                val progress = (-offsetVal / fullStepPx).coerceIn(0f, 1f)
                val nextX = fullStepPx + offsetVal
                CoverCard(
                    song = currentNextSong,
                    coverWidthDp = coverWidthDp,
                    translationX = nextX,
                    scale = 0.88f + 0.12f * progress,
                    alpha = 0.40f + 0.60f * progress,
                )
            }

            val currentScale = 1f - 0.12f * (abs(offsetVal) / fullStepPx).coerceIn(0f, 1f)
            val currentAlpha = 1f - 0.40f * (abs(offsetVal) / fullStepPx).coerceIn(0f, 1f)
            CoverCard(
                song = currentSong,
                coverWidthDp = coverWidthDp,
                translationX = offsetVal,
                scale = currentScale,
                alpha = currentAlpha,
            )
        }
    }
}

@Composable
private fun CoverCard(
    song: Song?,
    coverWidthDp: androidx.compose.ui.unit.Dp,
    translationX: Float,
    scale: Float,
    alpha: Float,
) {
    val candidates =
        remember(song) {
            MobileCoverCacheResolver.resolveCandidates(song)
        }

    Box(
        modifier =
            Modifier
                .offset { IntOffset(x = translationX.roundToInt(), y = 0) }
                .size(coverWidthDp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }.shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(12.dp),
                    spotColor = Color.Black.copy(alpha = 0.35f),
                    ambientColor = Color.Black.copy(alpha = 0.18f),
                    clip = false,
                ).clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AlbumArtImage(
            coverUrl = song?.coverUrl,
            candidates = candidates,
            contentDescription = song?.name ?: "封面",
            modifier = Modifier.fillMaxSize(),
        )
    }
}
