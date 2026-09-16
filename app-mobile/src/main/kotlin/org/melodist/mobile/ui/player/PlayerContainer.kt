package org.melodist.mobile.ui.player

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.melodist.playback.PlaybackManager
import kotlin.math.roundToInt

enum class SheetExpandDirection {
    BottomToTop,
    TopToBottom,
}

class TopSheetDragController(
    val onDragStart: () -> Unit,
    val onDrag: (dragAmount: Float) -> Unit,
    val onDragEnd: (velocityY: Float, onCollapse: () -> Unit) -> Unit,
    val onDragCancel: (onCollapse: () -> Unit) -> Unit,
)

val LocalTopSheetDragController = compositionLocalOf<TopSheetDragController?> { null }

@Composable
fun PlayerContainer(
    modifier: Modifier = Modifier,
    isFullPlayerExpanded: Boolean? = null,
    onFullPlayerExpandChange: ((Boolean) -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    var internalExpanded by remember { mutableStateOf(false) }
    val effectiveExpanded = isFullPlayerExpanded ?: internalExpanded

    fun setExpanded(expanded: Boolean) {
        if (onFullPlayerExpandChange != null) {
            onFullPlayerExpandChange(expanded)
        } else {
            internalExpanded = expanded
        }
    }

    val currentSong by PlaybackManager.currentSong.collectAsState()
    val isPlaying by PlaybackManager.isPlaying.collectAsState()
    val loopMode by PlaybackManager.loopMode.collectAsState()
    val lyrics by PlaybackManager.lyrics.collectAsState()
    val remotePrevSong by PlaybackManager.remotePrevSong.collectAsState()
    val remoteNextSong by PlaybackManager.remoteNextSong.collectAsState()

    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenHeightPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val thresholdPx = with(density) { 72.dp.toPx() }

    var activeDirection by remember { mutableStateOf(SheetExpandDirection.BottomToTop) }
    val sheetOffsetY = remember { Animatable(if (effectiveExpanded) 0f else screenHeightPx) }

    LaunchedEffect(effectiveExpanded, screenHeightPx) {
        if (screenHeightPx > 0f) {
            val collapsedTarget =
                when (activeDirection) {
                    SheetExpandDirection.BottomToTop -> screenHeightPx
                    SheetExpandDirection.TopToBottom -> -screenHeightPx
                }
            if (effectiveExpanded && sheetOffsetY.value != 0f) {
                sheetOffsetY.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            } else if (!effectiveExpanded && sheetOffsetY.value != collapsedTarget) {
                if (sheetOffsetY.value == 0f) {
                    sheetOffsetY.snapTo(collapsedTarget)
                } else {
                    sheetOffsetY.animateTo(collapsedTarget, tween(240, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    val isBackEnabled =
        when (activeDirection) {
            SheetExpandDirection.BottomToTop -> effectiveExpanded || sheetOffsetY.value < screenHeightPx - 10f
            SheetExpandDirection.TopToBottom -> effectiveExpanded || sheetOffsetY.value > -screenHeightPx + 10f
        }

    PredictiveBackHandler(enabled = isBackEnabled) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                val targetOffset =
                    when (activeDirection) {
                        SheetExpandDirection.BottomToTop -> screenHeightPx * backEvent.progress * 0.25f
                        SheetExpandDirection.TopToBottom -> -screenHeightPx + (screenHeightPx * (1f - backEvent.progress) * 0.25f)
                    }
                sheetOffsetY.snapTo(targetOffset)
            }
            sheetOffsetY.animateTo(screenHeightPx, tween(240, easing = FastOutSlowInEasing))
            setExpanded(false)
            activeDirection = SheetExpandDirection.BottomToTop
        } catch (_: CancellationException) {
            sheetOffsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
        }
    }

    val topSheetDragController = remember(screenHeightPx, thresholdPx) {
        TopSheetDragController(
            onDragStart = {
                activeDirection = SheetExpandDirection.TopToBottom
                scope.launch {
                    if (sheetOffsetY.value >= 0f) {
                        sheetOffsetY.snapTo(-screenHeightPx)
                    }
                    sheetOffsetY.stop()
                }
            },
            onDrag = { dragAmount ->
                activeDirection = SheetExpandDirection.TopToBottom
                scope.launch {
                    val current = if (sheetOffsetY.value > 0f) -screenHeightPx else sheetOffsetY.value
                    val target = (current + dragAmount).coerceIn(-screenHeightPx, 0f)
                    sheetOffsetY.snapTo(target)
                }
            },
            onDragEnd = { velocityY, onCollapse ->
                scope.launch {
                    activeDirection = SheetExpandDirection.TopToBottom
                    val pulledPx = screenHeightPx + sheetOffsetY.value
                    val expandThreshold = (screenHeightPx * 0.22f).coerceAtLeast(thresholdPx)
                    if (pulledPx > expandThreshold || velocityY > 650f) {
                        sheetOffsetY.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
                        setExpanded(true)
                        // 展开后统一为标准播放界面状态，后续手势与正常播放界面一致（再次下滑关闭）
                        activeDirection = SheetExpandDirection.BottomToTop
                    } else {
                        sheetOffsetY.animateTo(-screenHeightPx, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                        setExpanded(false)
                        onCollapse()
                        activeDirection = SheetExpandDirection.BottomToTop
                        sheetOffsetY.snapTo(screenHeightPx)
                    }
                }
            },
            onDragCancel = { onCollapse ->
                scope.launch {
                    activeDirection = SheetExpandDirection.TopToBottom
                    sheetOffsetY.animateTo(-screenHeightPx, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                    setExpanded(false)
                    onCollapse()
                    activeDirection = SheetExpandDirection.BottomToTop
                    sheetOffsetY.snapTo(screenHeightPx)
                }
            },
        )
    }

    val isDark = isSystemInDarkTheme()

    // 只要全屏播放器未展开，且不是正在从底部拉升到顶，底部的 MiniPlayerBar 均保持可见
    val isMiniPlayerVisible by remember {
        derivedStateOf {
            currentSong != null && !effectiveExpanded && (
                activeDirection != SheetExpandDirection.BottomToTop || sheetOffsetY.value > 10f
            )
        }
    }

    CompositionLocalProvider(LocalTopSheetDragController provides topSheetDragController) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (isMiniPlayerVisible) {
                    MiniPlayerBar(
                        song = currentSong,
                        isPlaying = isPlaying,
                        progressFraction = {
                            val dur = PlaybackManager.durationMs.value
                            val pos = PlaybackManager.currentPositionMs.value
                            if (dur > 0L) {
                                (pos.toFloat() / dur).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        onTogglePlayPause = { PlaybackManager.togglePlayPause() },
                        onPlayNext = { PlaybackManager.playNext() },
                        onPlayPrevious = { PlaybackManager.playPrevious() },
                        prevSong = remember(currentSong, remotePrevSong) { PlaybackManager.getPreviousSong() },
                        nextSong = remember(currentSong, remoteNextSong) { PlaybackManager.getNextSong() },
                        onClick = {
                            scope.launch {
                                activeDirection = SheetExpandDirection.BottomToTop
                                if (sheetOffsetY.value < 0f) {
                                    sheetOffsetY.snapTo(screenHeightPx)
                                }
                                sheetOffsetY.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                                setExpanded(true)
                            }
                        },
                        onDragStart = {
                            activeDirection = SheetExpandDirection.BottomToTop
                            scope.launch {
                                if (sheetOffsetY.value < 0f) {
                                    sheetOffsetY.snapTo(screenHeightPx)
                                }
                                sheetOffsetY.stop()
                            }
                        },
                        onDragUp = { dragAmount ->
                            activeDirection = SheetExpandDirection.BottomToTop
                            scope.launch {
                                val current = if (sheetOffsetY.value < 0f) screenHeightPx else sheetOffsetY.value
                                val target = (current + dragAmount).coerceIn(0f, screenHeightPx)
                                sheetOffsetY.snapTo(target)
                            }
                        },
                        onDragUpEnd = { velocityY ->
                            scope.launch {
                                activeDirection = SheetExpandDirection.BottomToTop
                                val pulledPx = screenHeightPx - sheetOffsetY.value
                                val expandThreshold = (screenHeightPx * 0.22f).coerceAtLeast(thresholdPx)
                                if (pulledPx > expandThreshold || velocityY < -700f) {
                                    sheetOffsetY.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
                                    setExpanded(true)
                                } else {
                                    sheetOffsetY.animateTo(screenHeightPx, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                                    setExpanded(false)
                                }
                            }
                        },
                        onDragUpCancel = {
                            scope.launch {
                                activeDirection = SheetExpandDirection.BottomToTop
                                sheetOffsetY.animateTo(screenHeightPx, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                                setExpanded(false)
                            }
                        },
                        // pullFraction 读取放在 graphicsLayer{} 里，限制在 Drawing 阶段，不触发重组
                        modifier =
                            Modifier
                                .navigationBarsPadding()
                                .graphicsLayer {
                                    val pullFraction =
                                        if (activeDirection == SheetExpandDirection.BottomToTop) {
                                            ((screenHeightPx - sheetOffsetY.value) / with(density) { 160.dp.toPx() }).coerceIn(0f, 1f)
                                        } else {
                                            0f
                                        }
                                    alpha = 1f - pullFraction
                                },
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { innerPadding ->
            content(innerPadding)
        }
    }

    val isPanelVisible by remember {
        derivedStateOf {
            currentSong != null && (
                effectiveExpanded ||
                (activeDirection == SheetExpandDirection.BottomToTop && screenHeightPx > 0f && sheetOffsetY.value < screenHeightPx * 0.98f) ||
                (activeDirection == SheetExpandDirection.TopToBottom && screenHeightPx > 0f && sheetOffsetY.value > -screenHeightPx * 0.98f)
            )
        }
    }

    if (isPanelVisible) {
        val panelOffsetY =
            when (activeDirection) {
                SheetExpandDirection.BottomToTop -> sheetOffsetY.value.roundToInt().coerceIn(0, screenHeightPx.toInt())
                SheetExpandDirection.TopToBottom -> sheetOffsetY.value.roundToInt().coerceIn(-screenHeightPx.toInt(), 0)
            }
        val cornerShape =
            when (activeDirection) {
                SheetExpandDirection.BottomToTop -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                SheetExpandDirection.TopToBottom -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
            }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset {
                        IntOffset(0, panelOffsetY)
                    }.shadow(
                        elevation = if (panelOffsetY != 0) 16.dp else 0.dp,
                        shape = cornerShape,
                        spotColor = if (isDark) Color.Black.copy(alpha = 0.70f) else Color.Black.copy(alpha = 0.35f),
                        ambientColor = if (isDark) Color.Black.copy(alpha = 0.40f) else Color.Black.copy(alpha = 0.15f),
                        clip = false,
                    ).clip(cornerShape),
        ) {
            FullPlayerSheet(
                song = currentSong,
                isPlaying = isPlaying,
                loopMode = loopMode,
                lyrics = lyrics,
                onCollapse = {
                    scope.launch {
                        sheetOffsetY.animateTo(screenHeightPx, tween(240, easing = FastOutSlowInEasing))
                        setExpanded(false)
                        activeDirection = SheetExpandDirection.BottomToTop
                    }
                },
                onDragStart = {
                    scope.launch {
                        sheetOffsetY.stop()
                    }
                },
                onDragDown = { dragAmount ->
                    scope.launch {
                        sheetOffsetY.snapTo((sheetOffsetY.value + dragAmount).coerceIn(0f, screenHeightPx))
                    }
                },
                onDragDownEnd = { velocityY ->
                    scope.launch {
                        val dismissDistanceThreshold = (screenHeightPx * 0.32f).coerceAtLeast(thresholdPx)
                        val shouldDismiss =
                            when {
                                velocityY > 500f -> true
                                velocityY < -400f -> false
                                else -> sheetOffsetY.value > dismissDistanceThreshold
                            }
                        if (shouldDismiss) {
                            sheetOffsetY.animateTo(screenHeightPx, tween(200, easing = FastOutSlowInEasing))
                            setExpanded(false)
                            activeDirection = SheetExpandDirection.BottomToTop
                        } else {
                            sheetOffsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                        }
                    }
                },
                onDragDownCancel = {
                    scope.launch {
                        sheetOffsetY.animateTo(screenHeightPx, tween(200, easing = FastOutSlowInEasing))
                        setExpanded(false)
                        activeDirection = SheetExpandDirection.BottomToTop
                    }
                },
            )
        }
    }
    }
}
