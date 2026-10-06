package org.melodist.mobile.ui.player.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
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
import org.melodist.mobile.ui.theme.isAppInDarkTheme
import org.melodist.mobile.util.MobileCoverCacheResolver
import org.melodist.model.Song
import kotlin.math.abs
import kotlin.math.roundToInt

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.key

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
    shadowTint: Color = Color(0xFF0D1016),
    isDark: Boolean = isAppInDarkTheme(),
    bottomOverlay: (@Composable BoxScope.(dragFraction: Float) -> Unit)? = null,
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
        val coverWidthDp = maxWidth * 0.92f
        val coverWidthPx = with(density) { coverWidthDp.toPx() }
        val spacingPx = with(density) { 24.dp.toPx() }
        val fullStepPx = coverWidthPx + spacingPx

        val dragThresholdPx = coverWidthPx * 0.45f
        val minFlingDistancePx = with(density) { 72.dp.toPx() }
        val flingVelocityThreshold = 1000f

        val velocityTracker = remember { VelocityTracker() }

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
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val currentVal = dragOffsetX.value
                                val prospectiveOffset = currentVal + dragAmount
                                val isBlocked =
                                    (prospectiveOffset > 0f && currentPrevSong == null) ||
                                        (prospectiveOffset < 0f && currentNextSong == null)
                                val factor = if (isBlocked) 0.25f else 1.0f
                                val newOffset = currentVal + dragAmount * factor
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
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
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
                                                dampingRatio = Spring.DampingRatioNoBouncy,
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

            // 上一首卡片：常驻挂载，位移小于等于 0 时隐藏，避免反向滑动卸载重建导致图片重载闪白
            if (currentPrevSong != null) {
                key(currentPrevSong?.songMid ?: "prev") {
                    val isVisible = offsetVal > 0.5f
                    val progress = (offsetVal / fullStepPx).coerceIn(0f, 1f)
                    val prevX = -fullStepPx + offsetVal
                    CoverCard(
                        song = currentPrevSong,
                        coverWidthDp = coverWidthDp,
                        translationX = prevX,
                        scale = 0.92f + 0.08f * progress,
                        shadowTint = shadowTint,
                        isDark = isDark,
                        modifier =
                            Modifier.graphicsLayer {
                                alpha = if (isVisible) 1f else 0f
                            },
                    )
                }
            }

            // 下一首卡片：常驻挂载，位移大于等于 0 时隐藏，避免反向滑动卸载重建导致图片重载闪白
            if (currentNextSong != null) {
                key(currentNextSong?.songMid ?: "next") {
                    val isVisible = offsetVal < -0.5f
                    val progress = (-offsetVal / fullStepPx).coerceIn(0f, 1f)
                    val nextX = fullStepPx + offsetVal
                    CoverCard(
                        song = currentNextSong,
                        coverWidthDp = coverWidthDp,
                        translationX = nextX,
                        scale = 0.92f + 0.08f * progress,
                        shadowTint = shadowTint,
                        isDark = isDark,
                        modifier =
                            Modifier.graphicsLayer {
                                alpha = if (isVisible) 1f else 0f
                            },
                    )
                }
            }

            key(currentSong?.songMid ?: "current") {
                val currentScale = 1f - 0.08f * (abs(offsetVal) / fullStepPx).coerceIn(0f, 1f)
                val dragFraction = (abs(offsetVal) / fullStepPx).coerceIn(0f, 1f)
                CoverCard(
                    song = currentSong,
                    coverWidthDp = coverWidthDp,
                    translationX = offsetVal,
                    scale = currentScale,
                    shadowTint = shadowTint,
                    isDark = isDark,
                    overlay =
                        bottomOverlay?.let { overlay ->
                            {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                alpha = (1f - dragFraction * 2.5f).coerceIn(0f, 1f)
                                            },
                                ) {
                                    overlay(dragFraction)
                                }
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun CoverCard(
    song: Song?,
    coverWidthDp: androidx.compose.ui.unit.Dp,
    translationX: Float,
    scale: Float,
    shadowTint: Color,
    isDark: Boolean,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val candidates =
        remember(song) {
            MobileCoverCacheResolver.resolveCandidates(song)
        }

    val cardShape = RoundedCornerShape(8.dp)

    // 封面投影层
    val spotColor =
        if (isDark) {
            shadowTint.copy(alpha = 0.55f)
        } else {
            shadowTint.copy(alpha = 0.30f)
        }
    val ambientColor =
        if (isDark) {
            shadowTint.copy(alpha = 0.30f)
        } else {
            shadowTint.copy(alpha = 0.14f)
        }

    val borderModifier =
        if (isDark) {
            Modifier.border(
                width = 0.8.dp,
                brush =
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                Color.White.copy(alpha = 0.18f),
                                Color.White.copy(alpha = 0.05f),
                                Color.Transparent,
                            ),
                    ),
                shape = cardShape,
            )
        } else {
            Modifier.border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f),
                shape = cardShape,
            )
        }

    Box(
        modifier =
            Modifier
                .offset { IntOffset(x = translationX.roundToInt(), y = 0) }
                .size(coverWidthDp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        // 1. 底层实体圆角阴影板：固定 alpha，避免 RenderNode 离屏缓冲引发的裁切与阴影闪烁
        Spacer(
            modifier =
                Modifier
                    .fillMaxSize()
                    .shadow(
                        elevation = if (isDark) 16.dp else 12.dp,
                        shape = cardShape,
                        spotColor = spotColor,
                        ambientColor = ambientColor,
                        clip = false,
                    ).background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = cardShape,
                    ),
        )

        // 2. 顶层内容容器：严格按 cardShape 裁剪图片并附带边框，显式禁用内部 AlbumArtImage 默认的 10dp 圆角与 8dp 阴影
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(borderModifier)
                    .clip(cardShape),
            contentAlignment = Alignment.Center,
        ) {
            AlbumArtImage(
                coverUrl = song?.coverUrl,
                candidates = candidates,
                contentDescription = song?.name ?: "封面",
                shape = cardShape,
                elevation = 0.dp,
                border = null,
                modifier = Modifier.fillMaxSize(),
            )
            overlay?.invoke(this)
        }
    }
}
