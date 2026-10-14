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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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

    val currentSongState by rememberUpdatedState(currentSong)
    val currentPrevSong by rememberUpdatedState(prevSong)
    val currentNextSong by rememberUpdatedState(nextSong)
    val currentOnPlayNext by rememberUpdatedState(onPlayNext)
    val currentOnPlayPrevious by rememberUpdatedState(onPlayPrevious)

    var centerSlotIndex by remember { mutableIntStateOf(0) }
    var slotSongs by remember {
        mutableStateOf(listOf(currentSong, nextSong, prevSong))
    }
    var pendingTargetSongMid by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentSong, prevSong, nextSong, centerSlotIndex) {
        val target = pendingTargetSongMid
        if (target != null) {
            if (currentSong?.songMid == target) {
                pendingTargetSongMid = null
                val prevSlot = (centerSlotIndex - 1).floorMod(3)
                val nextSlot = (centerSlotIndex + 1).floorMod(3)
                val updated = slotSongs.toMutableList()
                updated[centerSlotIndex] = currentSong
                updated[nextSlot] = nextSong
                updated[prevSlot] = prevSong
                slotSongs = updated
            }
        } else {
            val prevSlot = (centerSlotIndex - 1).floorMod(3)
            val nextSlot = (centerSlotIndex + 1).floorMod(3)
            val updated = slotSongs.toMutableList()
            updated[centerSlotIndex] = currentSong
            updated[nextSlot] = nextSong
            updated[prevSlot] = prevSong
            slotSongs = updated
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val coverFraction = if (maxHeight < maxWidth) 0.96f else 0.92f
        val coverWidthDp = minOf(maxWidth, maxHeight) * coverFraction
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
                                        val targetSong = currentNextSong
                                        pendingTargetSongMid = targetSong?.songMid
                                        centerSlotIndex = (centerSlotIndex + 1).floorMod(3)
                                        dragOffsetX.snapTo(0f)
                                        currentOnPlayNext()
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
                                        val targetSong = currentPrevSong
                                        pendingTargetSongMid = targetSong?.songMid
                                        centerSlotIndex = (centerSlotIndex - 1).floorMod(3)
                                        dragOffsetX.snapTo(0f)
                                        currentOnPlayPrevious()
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

            val prevSlot = (centerSlotIndex - 1).floorMod(3)
            val nextSlot = (centerSlotIndex + 1).floorMod(3)
            val renderOrder = listOf(prevSlot, nextSlot, centerSlotIndex)

            for (slotIndex in renderOrder) {
                key(slotIndex) {
                    val relativeStep =
                        when (slotIndex) {
                            centerSlotIndex -> 0
                            nextSlot -> 1
                            else -> -1
                        }

                    val slotSong = slotSongs.getOrNull(slotIndex)

                    if (slotSong != null) {
                        val baseX = relativeStep * fullStepPx
                        val cardX = baseX + offsetVal

                        val isCenter = relativeStep == 0
                        val isPrev = relativeStep == -1
                        val isNext = relativeStep == 1

                        val scale =
                            if (isCenter) {
                                1f - 0.08f * (abs(offsetVal) / fullStepPx).coerceIn(0f, 1f)
                            } else {
                                val progress =
                                    if (isPrev) {
                                        (offsetVal / fullStepPx).coerceIn(0f, 1f)
                                    } else {
                                        (-offsetVal / fullStepPx).coerceIn(0f, 1f)
                                    }
                                0.92f + 0.08f * progress
                            }

                        val cardAlpha =
                            when {
                                isCenter -> 1f
                                isPrev -> if (offsetVal > 0.5f) 1f else 0f
                                isNext -> if (offsetVal < -0.5f) 1f else 0f
                                else -> 0f
                            }

                        val dragFraction = (abs(offsetVal) / fullStepPx).coerceIn(0f, 1f)
                        val overlayComposable: (@Composable BoxScope.() -> Unit)? =
                            if (isCenter && bottomOverlay != null) {
                                {
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxSize()
                                                .graphicsLayer {
                                                    alpha = (1f - dragFraction * 2.5f).coerceIn(0f, 1f)
                                                },
                                    ) {
                                        bottomOverlay(dragFraction)
                                    }
                                }
                            } else {
                                null
                            }

                        CoverCard(
                            song = slotSong,
                            coverWidthDp = coverWidthDp,
                            translationX = cardX,
                            scale = scale,
                            shadowTint = shadowTint,
                            isDark = isDark,
                            overlay = overlayComposable,
                            modifier =
                                Modifier.graphicsLayer {
                                    alpha = cardAlpha
                                },
                        )
                    }
                }
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
        remember(song?.songMid, song?.coverUrl) {
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
        // 阴影层
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

        // 封面卡片容器
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
                songMid = song?.songMid,
                contentDescription = song?.name ?: "封面",
                shape = cardShape,
                elevation = 0.dp,
                border = null,
                crossfade = false,
                modifier = Modifier.fillMaxSize(),
            )
            overlay?.invoke(this)
        }
    }
}

private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod
