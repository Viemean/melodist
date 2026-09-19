package org.melodist.mobile.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.melodist.data.AppSettingsManager
import org.melodist.model.LyricLine
import org.melodist.model.Song
import kotlin.math.abs

@Composable
fun MobileLyricsView(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    lyricOffsetMs: Long = org.melodist.playback.PlaybackManager.currentLyricOffsetMs.collectAsState().value,
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = Color.White.copy(alpha = 0.88f),
    transColor: Color = Color.White.copy(alpha = 0.60f),
    song: Song? = null,
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "纯音乐，请静心欣赏",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val settings by AppSettingsManager.settings.collectAsState()
    val baseFontSize = settings.lyricFontSize.titleSp.sp
    val baseLineHeight = (settings.lyricFontSize.titleSp * 1.38f).sp
    val transFontSize = settings.lyricFontSize.subSp.sp
    val transLineHeight = (settings.lyricFontSize.subSp * 1.40f).sp

    val effectivePositionMs = currentPositionMs + lyricOffsetMs

    val activeIndex =
        remember(lyrics, effectivePositionMs) {
            val index = lyrics.indexOfLast { it.timestampMs <= effectivePositionMs }
            if (index == -1) 0 else index
        }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewportHeightPx = with(density) { maxHeight.roundToPx() }
        val halfHeight = maxHeight / 2

        val estimatedItemHeight = with(density) { (settings.lyricFontSize.titleSp * 2.2f).dp.roundToPx() }
        val centerScrollOffset = -((viewportHeightPx - estimatedItemHeight) / 2)
        val initialItemIndex = (activeIndex + 1).coerceIn(0, lyrics.size)

        val listState =
            rememberLazyListState(
                initialFirstVisibleItemIndex = initialItemIndex,
                initialFirstVisibleItemScrollOffset = centerScrollOffset,
            )
        val isDragged by listState.interactionSource.collectIsDraggedAsState()
        var isUserInteracting by remember { mutableStateOf(false) }
        var isInitialAligned by remember { mutableStateOf(false) }

        // 仅在用户手指手动拖拽歌词时暂停自动滚动，手指抬起 3 秒后自动恢复并居中平滑对齐
        LaunchedEffect(isDragged) {
            if (isDragged) {
                isUserInteracting = true
            } else if (isUserInteracting) {
                delay(3000L)
                isUserInteracting = false
            }
        }

        val centerVisibleLyricIndex by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                if (layoutInfo.visibleItemsInfo.isEmpty()) return@derivedStateOf activeIndex
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                val centerItem = layoutInfo.visibleItemsInfo.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                }
                val lineIndex = centerItem?.index?.minus(1) ?: activeIndex
                lineIndex.coerceIn(0, lyrics.size - 1)
            }
        }

        // 全面接管模式下同步歌词滚动视图至 TV
        LaunchedEffect(centerVisibleLyricIndex, isUserInteracting) {
            if (isUserInteracting) {
                org.melodist.mobile.connect.MobileConnectManager.sendLyricsScroll(
                    lineIndex = centerVisibleLyricIndex,
                    isUserScrolling = true,
                )
            } else {
                org.melodist.mobile.connect.MobileConnectManager.sendLyricsScroll(
                    lineIndex = activeIndex,
                    isUserScrolling = false,
                )
            }
        }

        // 歌曲播放行进或跳转时，整屏歌词平滑滚动使高亮行居中
        LaunchedEffect(activeIndex, isUserInteracting) {
            if (!isUserInteracting && activeIndex in lyrics.indices) {
                val targetIndex = activeIndex + 1
                val layoutInfo = listState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height.takeIf { it > 0 } ?: viewportHeightPx
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                val currentCenterOffset = -((viewportHeight - estimatedItemHeight) / 2)

                val targetItem = layoutInfo.visibleItemsInfo.find { it.index == targetIndex }
                if (!isInitialAligned) {
                    isInitialAligned = true
                    if (targetItem != null) {
                        val itemCenter = targetItem.offset + targetItem.size / 2
                        val diff = (itemCenter - viewportCenter).toFloat()
                        if (abs(diff) > 1.5f) {
                            listState.scrollBy(diff)
                        }
                    } else {
                        listState.scrollToItem(targetIndex, currentCenterOffset)
                    }
                    return@LaunchedEffect
                }

                if (targetItem != null) {
                    val itemCenter = targetItem.offset + targetItem.size / 2
                    val diff = (itemCenter - viewportCenter).toFloat()
                    if (abs(diff) > 1.5f) {
                        listState.animateScrollBy(
                            value = diff,
                            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                        )
                    }
                } else {
                    listState.animateScrollToItem(
                        index = targetIndex,
                        scrollOffset = currentCenterOffset,
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(key = "top_spacer") {
                Spacer(modifier = Modifier.height(halfHeight))
            }

            itemsIndexed(
                items = lyrics,
                key = { index, line -> "${line.timestampMs}_$index" },
            ) { index, line ->
                val isCurrent = index == activeIndex
                val distance = abs(index - activeIndex)
                val alpha by animateFloatAsState(
                    targetValue = if (isCurrent) 1.0f else (0.76f - distance * 0.05f).coerceIn(0.50f, 0.76f),
                    animationSpec = tween(300),
                    label = "lyric_alpha",
                )
                val scale by animateFloatAsState(
                    targetValue = if (isCurrent) 1.05f else 0.96f,
                    animationSpec = tween(300),
                    label = "lyric_scale",
                )

                LyricLineItem(
                    line = line,
                    isCurrent = isCurrent,
                    currentPositionMs = if (isCurrent) effectivePositionMs else 0L,
                    alpha = alpha,
                    scale = scale,
                    highlightColor = highlightColor,
                    textColor = textColor,
                    transColor = transColor,
                    fontSize = baseFontSize,
                    lineHeight = baseLineHeight,
                    transFontSize = transFontSize,
                    transLineHeight = transLineHeight,
                    enableWordByWordAnim = settings.enableWordByWordAnim,
                    showBilingualLyrics = settings.showBilingualLyrics,
                    onClick = {
                        isUserInteracting = false
                        onSeekTo((line.timestampMs - lyricOffsetMs).coerceAtLeast(0L))
                    },
                )
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(halfHeight))
            }
        }
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    isCurrent: Boolean,
    currentPositionMs: Long,
    alpha: Float,
    scale: Float,
    highlightColor: Color,
    textColor: Color,
    transColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    transFontSize: TextUnit,
    transLineHeight: TextUnit,
    enableWordByWordAnim: Boolean,
    showBilingualLyrics: Boolean,
    onClick: () -> Unit,
) {
    val currentLineElapsedMs = if (isCurrent) (currentPositionMs - line.timestampMs).coerceAtLeast(0L) else 0L

    val defaultTextColor = textColor
    val defaultTransColor = transColor

    val animatedTransColor by animateColorAsState(
        targetValue = if (isCurrent) highlightColor.copy(alpha = 0.88f) else defaultTransColor,
        animationSpec = tween(250),
        label = "trans_color",
    )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                }.clickable(onClick = onClick)
                .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (enableWordByWordAnim && line.isWordSynced && isCurrent) {
            val annotatedString =
                buildAnnotatedString {
                    line.words.forEach { wordSpan ->
                        val wordStart = wordSpan.offsetMs
                        val isSang = currentLineElapsedMs >= wordStart
                        val color = if (isSang) highlightColor else defaultTextColor
                        val weight = if (isSang) FontWeight.Bold else FontWeight.Medium

                        pushStyle(SpanStyle(color = color, fontWeight = weight, fontSize = fontSize))
                        append(wordSpan.word)
                        pop()
                    }
                }
            Text(
                text = annotatedString,
                style = MaterialTheme.typography.titleLarge,
                fontSize = fontSize,
                lineHeight = lineHeight,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val textColor by animateColorAsState(
                targetValue = if (isCurrent) highlightColor else defaultTextColor,
                animationSpec = tween(250),
                label = "line_color",
            )
            Text(
                text = line.text,
                color = textColor,
                fontSize = fontSize,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                lineHeight = lineHeight,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (showBilingualLyrics && line.hasTranslation) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = line.transText,
                color = animatedTransColor,
                fontSize = transFontSize,
                lineHeight = transLineHeight,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
