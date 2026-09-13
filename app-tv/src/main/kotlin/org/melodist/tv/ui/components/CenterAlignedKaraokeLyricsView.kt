package org.melodist.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.melodist.data.AppSettingsManager
import org.melodist.model.LyricLine
import org.melodist.tv.ui.theme.MelodistColors
import kotlin.math.abs

@Composable
fun CenterAlignedKaraokeLyricsView(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
    highlightColor: Color = MelodistColors.AccentGreen,
) {
    val settings by AppSettingsManager.settings.collectAsState()
    val baseFontSize = settings.lyricFontSize.spValue
    val enableWordAnim = settings.enableWordByWordAnim
    val showBilingual = settings.showBilingualLyrics

    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "纯音乐，请静心欣赏",
                color = MelodistColors.TextMuted,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    // 定位当前正在演唱的歌词行索引
    val activeIndex =
        remember(lyrics, currentPositionMs) {
            val index = lyrics.indexOfLast { it.timestampMs <= currentPositionMs }
            if (index == -1) 0 else index
        }

    val listState = rememberLazyListState()

    // 自动平滑居中滚动至当前行
    LaunchedEffect(activeIndex) {
        if (activeIndex in lyrics.indices) {
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -80,
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 120.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(
            items = lyrics,
            key = { index, line -> "${line.timestampMs}_$index" },
        ) { index, line ->
            val isCurrent = index == activeIndex
            val distance = abs(index - activeIndex)

            val alpha =
                when (distance) {
                    0 -> 1.0f
                    1 -> 0.86f
                    2 -> 0.72f
                    3 -> 0.58f
                    else -> 0.44f
                }

            LyricLineItem(
                line = line,
                isCurrent = isCurrent,
                alphaVal = alpha,
                currentPositionMs = if (isCurrent) currentPositionMs else 0L,
                baseFontSize = baseFontSize,
                enableWordAnim = enableWordAnim,
                showBilingual = showBilingual,
                highlightColor = highlightColor,
            )
        }
    }
}

private val UnplayedLyricColor = Color.White.copy(alpha = 0.70f)

@Composable
private fun LyricLineItem(
    line: LyricLine,
    isCurrent: Boolean,
    alphaVal: Float = 1.0f,
    currentPositionMs: Long,
    baseFontSize: Int = 24,
    enableWordAnim: Boolean = true,
    showBilingual: Boolean = true,
    highlightColor: Color = MelodistColors.AccentGreen,
) {
    // 渲染逐字或整行
    val currentLineElapsedMs = if (isCurrent) (currentPositionMs - line.timestampMs).coerceAtLeast(0) else 0L

    // 歌词行切换动画 (保持在 graphicsLayer 块内读取避免 Composable 重组)
    val animatedScale =
        animateFloatAsState(
            targetValue = if (isCurrent) 1.08f else 1.0f,
            animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
            label = "LyricScale",
        )
    val animatedAlpha =
        animateFloatAsState(
            targetValue = alphaVal,
            animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
            label = "LyricAlpha",
        )
    val animatedTransColor by animateColorAsState(
        targetValue =
            if (isCurrent) {
                highlightColor.copy(
                    alpha = 0.88f,
                )
            } else {
                Color.White.copy(alpha = (alphaVal * 0.88f).coerceIn(0.40f, 0.88f))
            },
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "LyricTransColor",
    )

    val fontSp = baseFontSize.sp
    val annotatedString =
        remember(line, isCurrent, currentLineElapsedMs, baseFontSize, enableWordAnim, highlightColor) {
            buildAnnotatedString {
                if (isCurrent && line.isWordSynced && enableWordAnim) {
                    line.words.forEach { wordSpan ->
                        val wordStart = wordSpan.offsetMs
                        val isSang = currentLineElapsedMs >= wordStart

                        val wordColor =
                            if (isSang) {
                                highlightColor
                            } else {
                                UnplayedLyricColor
                            }

                        pushStyle(
                            SpanStyle(
                                color = wordColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = fontSp,
                            ),
                        )
                        append(wordSpan.word)
                        pop()
                    }
                } else {
                    val color =
                        if (isCurrent) {
                            highlightColor
                        } else {
                            Color.White
                        }
                    pushStyle(
                        SpanStyle(
                            color = color,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            fontSize = fontSp,
                        ),
                    )
                    append(line.text)
                    pop()
                }
            }
        }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .padding(horizontal = 24.dp)
                .graphicsLayer {
                    scaleX = animatedScale.value
                    scaleY = animatedScale.value
                    this.alpha = animatedAlpha.value
                },
    ) {
        // 歌词原文渲染
        Text(
            text = annotatedString,
            textAlign = TextAlign.Center,
            lineHeight = (baseFontSize * 1.42f).sp,
        )

        // 译文渲染 (置于下方，颜色平滑过渡)
        if (line.hasTranslation && showBilingual) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = line.transText,
                color = animatedTransColor,
                fontSize = (baseFontSize * 0.70f).sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                lineHeight = (baseFontSize * 1.0f).sp,
            )
        }
    }
}
