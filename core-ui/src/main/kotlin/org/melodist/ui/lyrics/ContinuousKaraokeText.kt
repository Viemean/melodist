package org.melodist.ui.lyrics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import org.melodist.model.LyricLine

/**
 * 跨端无状态平滑卡拉 OK 逐字歌词原子渲染组件。
 *
 * 采用单层 TextLayout 结合 Draw 阶段硬件裁切 (clipRect)，实现平滑过渡流动。
 */
@Composable
fun ContinuousKaraokeText(
    line: LyricLine,
    currentLineElapsedMs: Long,
    highlightColor: Color,
    defaultTextColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val fullText =
        remember(line) {
            if (line.words.isNotEmpty()) {
                line.words.joinToString("") { it.word }
            } else {
                line.text
            }
        }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = fullText,
            color = Color.Transparent,
            style = MaterialTheme.typography.titleLarge,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            textAlign = textAlign,
            onTextLayout = { textLayoutResult = it },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        drawContent()
                        val layout = textLayoutResult ?: return@drawWithContent
                        val lineCount = layout.lineCount

                        for (lineIdx in 0 until lineCount) {
                            val lineStartOffset = layout.getLineStart(lineIdx)
                            val lineEndOffset = layout.getLineEnd(lineIdx)
                            val lineLeft = layout.getLineLeft(lineIdx)
                            val lineRight = layout.getLineRight(lineIdx)
                            val lineWidth = lineRight - lineLeft
                            if (lineWidth <= 0f) continue

                            var activeX = lineLeft

                            if (line.words.isEmpty()) {
                                if (currentLineElapsedMs >= 0) {
                                    activeX = lineRight
                                }
                            } else {
                                var charCursor = 0

                                for (wordSpan in line.words) {
                                    val wordLen = wordSpan.word.length
                                    val wordStartIdx = charCursor
                                    val wordEndIdx = charCursor + wordLen
                                    charCursor += wordLen

                                    if (wordEndIdx <= lineStartOffset) {
                                        continue
                                    }
                                    if (wordStartIdx >= lineEndOffset) {
                                        break
                                    }

                                    val wordStartMs = wordSpan.offsetMs
                                    val wordDurMs = wordSpan.durationMs.coerceAtLeast(1L)
                                    val wordEndMs = wordStartMs + wordDurMs

                                    val clampedStartIdx = wordStartIdx.coerceAtLeast(lineStartOffset)
                                    val clampedEndIdx = wordEndIdx.coerceAtMost(lineEndOffset)

                                    val wordXLeft =
                                        if (clampedStartIdx <= lineStartOffset) {
                                            lineLeft
                                        } else {
                                            layout.getHorizontalPosition(clampedStartIdx, usePrimaryDirection = true)
                                        }

                                    val wordXRight =
                                        if (clampedEndIdx >= lineEndOffset) {
                                            lineRight
                                        } else {
                                            layout.getHorizontalPosition(clampedEndIdx, usePrimaryDirection = true)
                                        }

                                    val wordPixelWidth = (wordXRight - wordXLeft).coerceAtLeast(0f)

                                    if (currentLineElapsedMs >= wordEndMs) {
                                        if (wordXRight > activeX) activeX = wordXRight
                                    } else if (currentLineElapsedMs > wordStartMs) {
                                        val progress =
                                            ((currentLineElapsedMs - wordStartMs).toFloat() / wordDurMs.toFloat()).coerceIn(
                                                0f,
                                                1f,
                                            )
                                        val currentWordX = wordXLeft + wordPixelWidth * progress
                                        if (currentWordX > activeX) activeX = currentWordX
                                        break
                                    } else {
                                        break
                                    }
                                }
                            }

                            val lineTop = layout.getLineTop(lineIdx)
                            val lineBottom = layout.getLineBottom(lineIdx)

                            // 绘制已播放高亮部分
                            if (activeX > lineLeft) {
                                clipRect(
                                    left = lineLeft,
                                    top = lineTop,
                                    right = activeX,
                                    bottom = lineBottom,
                                ) {
                                    drawText(
                                        textLayoutResult = layout,
                                        color = highlightColor,
                                    )
                                }
                            }

                            // 绘制未播放底色部分
                            if (activeX < lineRight) {
                                clipRect(
                                    left = activeX,
                                    top = lineTop,
                                    right = lineRight,
                                    bottom = lineBottom,
                                ) {
                                    drawText(
                                        textLayoutResult = layout,
                                        color = defaultTextColor,
                                    )
                                }
                            }
                        }
                    },
        )
    }
}
