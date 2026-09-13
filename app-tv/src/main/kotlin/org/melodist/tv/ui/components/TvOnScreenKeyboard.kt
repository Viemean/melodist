package org.melodist.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.melodist.tv.ui.theme.MelodistColors

@Composable
fun TvOnScreenKeyboard(
    firstKeyRequester: FocusRequester,
    fKeyRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    onCharacterInput: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val digits = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
    val letters =
        listOf(
            listOf('A', 'B', 'C', 'D', 'E', 'F'),
            listOf('G', 'H', 'I', 'J', 'K', 'L'),
            listOf('M', 'N', 'O', 'P', 'Q', 'R'),
            listOf('S', 'T', 'U', 'V', 'W', 'X'),
            listOf('Y', 'Z'),
        )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 数字排 (10 列)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            digits.forEachIndexed { idx, digit ->
                val isLastInRow = idx == digits.size - 1
                KeyButton(
                    text = digit.toString(),
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .then(
                                if (isLastInRow) {
                                    Modifier.focusProperties { right = rightFocusRequester }
                                } else {
                                    Modifier
                                },
                            ),
                    onClick = { onCharacterInput(digit) },
                )
            }
        }

        // 字母矩阵排 (6 列)
        letters.forEachIndexed { rowIdx, rowLetters ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowLetters.forEachIndexed { colIdx, letter ->
                    val isFirstKey = rowIdx == 0 && colIdx == 0
                    val isFKey = rowIdx == 0 && letter == 'F'
                    val isLastInRow = colIdx == rowLetters.size - 1
                    KeyButton(
                        text = letter.toString(),
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .then(if (isFirstKey) Modifier.focusRequester(firstKeyRequester) else Modifier)
                                .then(if (isFKey) Modifier.focusRequester(fKeyRequester) else Modifier)
                                .then(
                                    if (isLastInRow) {
                                        Modifier.focusProperties { right = rightFocusRequester }
                                    } else {
                                        Modifier
                                    },
                                ),
                        onClick = { onCharacterInput(letter) },
                    )
                }

                // 补齐最后一行不足 6 列的部分（空格键）
                if (rowLetters.size < 6) {
                    val remainingCols = 6 - rowLetters.size
                    KeyButton(
                        text = "SPACE",
                        modifier =
                            Modifier
                                .weight(remainingCols.toFloat())
                                .height(40.dp)
                                .focusProperties { right = rightFocusRequester },
                        onClick = { onCharacterInput(' ') },
                    )
                }
            }
        }

        // 底部功能键排 (仅显示“搜索”)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyButton(
                text = "退格",
                modifier =
                    Modifier
                        .weight(1f)
                        .height(42.dp),
                onClick = onBackspace,
            )
            KeyButton(
                text = "清空",
                modifier =
                    Modifier
                        .weight(1f)
                        .height(42.dp),
                onClick = onClear,
            )
            KeyButton(
                text = "搜索",
                modifier =
                    Modifier
                        .weight(1.5f)
                        .height(42.dp)
                        .focusProperties { right = rightFocusRequester },
                isAccent = true,
                onClick = onSearch,
            )
        }
    }
}

@Composable
private fun KeyButton(
    text: String,
    modifier: Modifier = Modifier,
    isAccent: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val baseBgColor =
        if (isAccent) {
            MelodistColors.AccentGreen.copy(alpha = 0.22f)
        } else {
            Color.White.copy(alpha = 0.12f)
        }
    val baseBorderColor =
        if (isAccent) {
            MelodistColors.AccentGreen.copy(alpha = 0.50f)
        } else {
            Color.White.copy(alpha = 0.18f)
        }
    val baseContentColor =
        if (isAccent) {
            MelodistColors.AccentGreen
        } else {
            MelodistColors.TextPrimary
        }

    Box(
        modifier =
            modifier
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else baseBorderColor,
                        ),
                    shape = RoundedCornerShape(8.dp),
                ).background(
                    color = if (isFocused) Color.White else baseBgColor,
                    shape = RoundedCornerShape(8.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = if (text.length > 2) 13.sp else 16.sp,
            fontWeight = if (isFocused || isAccent) FontWeight.Bold else FontWeight.Medium,
            color = if (isFocused) Color.Black else baseContentColor,
        )
    }
}
