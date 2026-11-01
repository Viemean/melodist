package org.melodist.tv.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import org.melodist.playback.PlaybackManager
import org.melodist.playback.SleepTimerManager
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberMonetSurfaceColor
import org.melodist.tv.ui.theme.toMonetContainer

@Composable
fun SleepTimerTvDialog(
    accentColor: Color = MelodistColors.AccentGreen,
    onDismiss: () -> Unit = {},
) {
    val currentSong by PlaybackManager.currentSong.collectAsState()
    val monetSurfaceColor = rememberMonetSurfaceColor(currentSong?.coverUrl)
    val dialogBackgroundColor =
        remember(monetSurfaceColor) {
            monetSurfaceColor.toMonetContainer(elevation = 0.08f).copy(alpha = 0.95f)
        }

    val isTimerActive by SleepTimerManager.isTimerActive.collectAsState()
    val selectedMinutes by SleepTimerManager.selectedMinutes.collectAsState()
    val remainingSeconds by SleepTimerManager.remainingSeconds.collectAsState()
    val isWaitingForSongEnd by SleepTimerManager.isWaitingForSongEnd.collectAsState()

    var showCustomInputDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setDimAmount(0.28f)
        }

        Box(
            modifier =
                Modifier
                    .width(638.dp)
                    .wrapContentHeight()
                    .clip(MelodistShapes.DialogCorner)
                    .background(dialogBackgroundColor)
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), MelodistShapes.DialogCorner)
                    .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 顶部说明与当前倒计时状态
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "定时停止播放",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.90f),
                    )
                    val statusText =
                        when {
                            !isTimerActive -> {
                                "默认播完当前曲目后停止"
                            }

                            isWaitingForSongEnd -> {
                                "时间已到，正在播完本曲后停止"
                            }

                            else -> {
                                val mins = remainingSeconds / 60
                                val secs = remainingSeconds % 60
                                "倒计时 ${mins}分${secs}秒（播完本曲后停止）"
                            }
                        }
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = if (isTimerActive) accentColor else Color.White.copy(alpha = 0.60f),
                    )
                }

                // 预设第 1 行：15, 30, 60, 90
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf(15, 30, 60, 90).forEach { mins ->
                        val isSelected = isTimerActive && selectedMinutes == mins
                        TimerPresetOptionItem(
                            title = "$mins",
                            subtitle = if (isSelected) "当前设定" else "分钟",
                            isSelected = isSelected,
                            accentColor = accentColor,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (isSelected) {
                                    SleepTimerManager.cancelTimer()
                                } else {
                                    SleepTimerManager.startTimer(mins)
                                }
                                onDismiss()
                            },
                        )
                    }
                }

                // 预设第 2 行：120, 自定义, 关闭
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val is120Selected = isTimerActive && selectedMinutes == 120
                    TimerPresetOptionItem(
                        title = "120",
                        subtitle = if (is120Selected) "当前设定" else "分钟",
                        isSelected = is120Selected,
                        accentColor = accentColor,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (is120Selected) {
                                SleepTimerManager.cancelTimer()
                            } else {
                                SleepTimerManager.startTimer(120)
                            }
                            onDismiss()
                        },
                    )

                    val isCustomSelected = isTimerActive && selectedMinutes != null && selectedMinutes !in listOf(15, 30, 60, 90, 120)
                    TimerPresetOptionItem(
                        title = "自定义",
                        subtitle = if (isCustomSelected) "${selectedMinutes}分钟" else "输入时长",
                        isSelected = isCustomSelected,
                        accentColor = accentColor,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showCustomInputDialog = true
                        },
                    )

                    TimerPresetOptionItem(
                        title = "关闭",
                        subtitle = if (isTimerActive) "停止定时" else "未开启",
                        isSelected = false,
                        accentColor = accentColor,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (isTimerActive) {
                                SleepTimerManager.cancelTimer()
                            }
                            onDismiss()
                        },
                    )

                    // 占位对齐第 1 行 4 格栅格
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (showCustomInputDialog) {
        CustomSleepTimerInputDialog(
            accentColor = accentColor,
            onConfirm = { minutes ->
                SleepTimerManager.startTimer(minutes)
                showCustomInputDialog = false
                onDismiss()
            },
            onDismissRequest = {
                showCustomInputDialog = false
            },
        )
    }
}

@Composable
private fun TimerPresetOptionItem(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bg =
        when {
            isFocused -> Color.White
            isSelected -> accentColor.copy(alpha = 0.22f)
            else -> Color.White.copy(alpha = 0.10f)
        }

    val titleColor =
        when {
            isFocused -> Color.Black
            isSelected -> accentColor
            else -> Color.White
        }

    val subtitleColor =
        when {
            isFocused -> if (isSelected) MelodistColors.FocusTeal else Color(0xFF475569)
            isSelected -> accentColor.copy(alpha = 0.90f)
            else -> Color.White.copy(alpha = 0.70f)
        }

    val border =
        when {
            isFocused -> Modifier.border(BorderStroke(2.5.dp, MelodistColors.FocusTeal), MelodistShapes.ButtonCorner)
            isSelected -> Modifier.border(BorderStroke(1.5.dp, accentColor.copy(alpha = 0.85f)), MelodistShapes.ButtonCorner)
            else -> Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)), MelodistShapes.ButtonCorner)
        }

    Box(
        modifier =
            modifier
                .height(54.dp)
                .then(border)
                .clip(MelodistShapes.ButtonCorner)
                .background(bg)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                color = titleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = subtitleColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CustomSleepTimerInputDialog(
    accentColor: Color,
    onConfirm: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var textValue by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val submitValue = {
        val minutes = textValue.toIntOrNull()
        if (minutes == null || minutes <= 0) {
            Toast.makeText(context, "请输入有效的分钟数（大于0）", Toast.LENGTH_SHORT).show()
        } else {
            onConfirm(minutes)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setDimAmount(0.40f)
        }

        Box(
            modifier =
                Modifier
                    .width(440.dp)
                    .wrapContentHeight()
                    .clip(MelodistShapes.DialogCorner)
                    .background(MelodistColors.ContainerDark)
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), MelodistShapes.DialogCorner)
                    .padding(24.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "自定义定时时长",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )

                Text(
                    text = "请输入定时关闭时间（分钟），到达时间后将完整播完当前曲目再自动停止播放",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.70f),
                    lineHeight = 18.sp,
                )

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(MelodistShapes.ButtonCorner)
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(BorderStroke(1.5.dp, MelodistColors.FocusTeal), MelodistShapes.ButtonCorner)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = textValue,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }
                            if (digits.length <= 4) {
                                textValue = digits
                            }
                        },
                        singleLine = true,
                        textStyle =
                            TextStyle(
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        cursorBrush = SolidColor(MelodistColors.FocusTeal),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = { submitValue() },
                            ),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                    )
                    if (textValue.isEmpty()) {
                        Text(
                            text = "例如 45",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 20.sp,
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f),
                        shape =
                            ButtonDefaults.shape(
                                shape = MelodistShapes.ButtonCorner,
                                focusedShape = MelodistShapes.ButtonCorner,
                            ),
                        colors =
                            ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.10f),
                                focusedContainerColor = Color.White,
                                contentColor = Color.White,
                                focusedContentColor = Color.Black,
                            ),
                    ) {
                        Text("取消", fontSize = 14.sp)
                    }

                    Button(
                        onClick = { submitValue() },
                        modifier = Modifier.weight(1f),
                        shape =
                            ButtonDefaults.shape(
                                shape = MelodistShapes.ButtonCorner,
                                focusedShape = MelodistShapes.ButtonCorner,
                            ),
                        colors =
                            ButtonDefaults.colors(
                                containerColor = MelodistColors.FocusTeal,
                                focusedContainerColor = Color.White,
                                contentColor = Color.Black,
                                focusedContentColor = Color.Black,
                            ),
                    ) {
                        Text("确定", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
