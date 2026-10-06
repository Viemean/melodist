package org.melodist.mobile.ui.components.songlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.melodist.mobile.util.SongSorter
import org.melodist.model.Song
import org.melodist.model.SongSortOrder
import kotlin.math.roundToInt

@Composable
fun SongListFastScroller(
    listState: LazyListState,
    totalItems: Int,
    headerCount: Int = 0,
    currentSongProvider: ((Int) -> Song?)? = null,
    sortOrder: SongSortOrder = SongSortOrder.DEFAULT,
    onDragStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (totalItems < 100) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var isVisibleByActivity by remember { mutableStateOf(false) }
    var dragProgressFraction by remember { mutableFloatStateOf(0f) }
    var currentScrolledIndex by remember { mutableIntStateOf(0) }

    // 监听列表滚动与拖拽：停止移动 3 秒后平滑隐去
    val isActivityActive = listState.isScrollInProgress || isDragging
    LaunchedEffect(isActivityActive) {
        if (isActivityActive) {
            isVisibleByActivity = true
        } else {
            delay(3000L)
            isVisibleByActivity = false
        }
    }

    // 拖拽释放后延时恢复悬浮操作栏
    LaunchedEffect(isDragging) {
        if (!isDragging) {
            delay(500)
            onDragStateChanged(false)
        } else {
            onDragStateChanged(true)
        }
    }

    // 非拖拽状态下根据 LazyListState 动态计算滑块高度比例
    val scrollProgress by remember(totalItems, headerCount) {
        derivedStateOf {
            val firstVisible = (listState.firstVisibleItemIndex - headerCount).coerceAtLeast(0)
            if (totalItems > 1) {
                (firstVisible.toFloat() / (totalItems - 1).toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }

    val activeFraction = if (isDragging) dragProgressFraction else scrollProgress

    // MD3 规范的加宽尺寸与弹性过渡动画
    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 15.dp else 10.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "FastScrollerWidth",
    )
    val thumbColor by animateColorAsState(
        targetValue =
            if (isDragging) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)
            },
        animationSpec = tween(150),
        label = "FastScrollerColor",
    )
    val thumbElevation by animateDpAsState(
        targetValue = if (isDragging) 4.dp else 1.5.dp,
        label = "FastScrollerElevation",
    )
    val scrollerAlpha by animateFloatAsState(
        targetValue = if (isVisibleByActivity || isDragging) 1f else 0f,
        animationSpec = tween(if (isVisibleByActivity || isDragging) 180 else 450),
        label = "FastScrollerAlpha",
    )

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxHeight()
                .graphicsLayer { alpha = scrollerAlpha },
        contentAlignment = Alignment.TopEnd,
    ) {
        val trackHeightPx = constraints.maxHeight.toFloat()
        val thumbHeightDp = 48.dp
        val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
        val maxTravelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)

        val thumbOffsetY = activeFraction * maxTravelPx

        // 索引指示气泡：沿 Y 轴动态跟随滑块手柄滑动
        val activeSong = currentSongProvider?.invoke(currentScrolledIndex)
        val initialLetter = remember(activeSong, sortOrder) {
            if (activeSong != null) {
                SongSorter.getInitial(activeSong, sortOrder)
            } else {
                ""
            }
        }

        val bubbleSizeDp = 52.dp
        val bubbleSizePx = with(density) { bubbleSizeDp.toPx() }
        val bubbleOffsetY =
            (thumbOffsetY + (thumbHeightPx - bubbleSizePx) / 2f)
                .coerceIn(8f, (trackHeightPx - bubbleSizePx - 8f).coerceAtLeast(0f))

        AnimatedVisibility(
            visible = isDragging && initialLetter.isNotBlank(),
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.8f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f),
            modifier =
                Modifier
                    .offset { IntOffset(x = 0, y = bubbleOffsetY.roundToInt()) }
                    .padding(end = 26.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 6.dp,
                tonalElevation = 6.dp,
                modifier = Modifier.size(bubbleSizeDp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = initialLetter,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        // 触摸热区（宽 36dp
        Box(
            modifier =
                Modifier
                    .width(36.dp)
                    .fillMaxHeight()
                    .pointerInput(totalItems, maxTravelPx) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                val frac = if (maxTravelPx > 0f) (offset.y / trackHeightPx).coerceIn(0f, 1f) else 0f
                                dragProgressFraction = frac
                                val target = (frac * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
                                currentScrolledIndex = target
                                scope.launch {
                                    listState.scrollToItem(headerCount + target)
                                }
                            },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                val frac = if (maxTravelPx > 0f) (change.position.y / trackHeightPx).coerceIn(0f, 1f) else 0f
                                dragProgressFraction = frac
                                val target = (frac * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
                                currentScrolledIndex = target
                                scope.launch {
                                    listState.scrollToItem(headerCount + target)
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                            },
                            onDragCancel = {
                                isDragging = false
                            },
                        )
                    },
            contentAlignment = Alignment.TopEnd,
        ) {
            // MD3 悬浮纯净胶囊滑柄（Floating Scrubber Thumb）
            Surface(
                shape = CircleShape,
                color = thumbColor,
                shadowElevation = thumbElevation,
                tonalElevation = thumbElevation,
                modifier =
                    Modifier
                        .offset { IntOffset(x = 0, y = thumbOffsetY.roundToInt()) }
                        .padding(end = 4.dp)
                        .size(width = thumbWidth, height = thumbHeightDp),
            ) {}
        }
    }
}
