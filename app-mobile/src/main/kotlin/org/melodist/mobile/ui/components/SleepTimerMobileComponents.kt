package org.melodist.mobile.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.melodist.playback.SleepTimerManager
import java.util.Locale

@Composable
fun SleepTimerActionBadge(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val isTimerActive by SleepTimerManager.isTimerActive.collectAsState()
    val remainingSeconds by SleepTimerManager.remainingSeconds.collectAsState()
    val isWaitingForSongEnd by SleepTimerManager.isWaitingForSongEnd.collectAsState()

    val formattedTime =
        remember(remainingSeconds) {
            val hours = remainingSeconds / 3600
            val minutes = (remainingSeconds % 3600) / 60
            val seconds = remainingSeconds % 60
            if (hours > 0) {
                String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
        }

    // 倒计时归零且正在等待当前单曲放完时重新变回计时器图标
    val showCountdownText = isTimerActive && remainingSeconds > 0 && !isWaitingForSongEnd

    AnimatedContent(
        targetState = showCountdownText,
        transitionSpec = {
            fadeIn() + expandHorizontally() togetherWith fadeOut() + shrinkHorizontally()
        },
        label = "sleepTimerBadgeAnim",
        modifier = modifier,
    ) { hasCountdown ->
        if (hasCountdown) {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 2.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Timer,
                        contentDescription = "定时停止",
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        } else {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color =
                    if (isWaitingForSongEnd) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
                    },
                contentColor =
                    if (isWaitingForSongEnd) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Timer,
                        contentDescription = "定时停止",
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun SleepTimerSelectDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val presetOptions = listOf(15, 30, 45, 60)
    var selectedPresetMinutes by remember { mutableIntStateOf(30) }
    var customMinutesText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "倒计时",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presetOptions.forEach { minutes ->
                        val isSelected = (customMinutesText.isBlank() && selectedPresetMinutes == minutes)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                customMinutesText = ""
                                selectedPresetMinutes = minutes
                            },
                            label = { Text("${minutes}分", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                        )
                    }
                }

                Text(
                    text = "自定义时长（分钟）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = customMinutesText,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }.take(4)
                        customMinutesText = filtered
                    },
                    placeholder = { Text("输入 1 ~ 720 分钟") },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                val minutes = customMinutesText.toIntOrNull() ?: selectedPresetMinutes
                                if (minutes in 1..720) {
                                    SleepTimerManager.startTimer(minutes)
                                    Toast.makeText(context, "已开启 $minutes 分钟倒计时", Toast.LENGTH_SHORT).show()
                                    onDismissRequest()
                                }
                            },
                        ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val customVal = customMinutesText.toIntOrNull()
                    val targetMinutes =
                        if (customVal != null && customVal > 0) {
                            customVal.coerceIn(1, 720)
                        } else {
                            selectedPresetMinutes
                        }
                    SleepTimerManager.startTimer(targetMinutes)
                    Toast.makeText(context, "已开启 $targetMinutes 分钟倒计时", Toast.LENGTH_SHORT).show()
                    onDismissRequest()
                },
            ) {
                Text("开启倒计时")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        },
    )
}

@Composable
fun SleepTimerActiveConfirmDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onResetTimer: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val remainingSeconds by SleepTimerManager.remainingSeconds.collectAsState()
    val isWaitingForSongEnd by SleepTimerManager.isWaitingForSongEnd.collectAsState()

    val formattedRemaining =
        remember(remainingSeconds) {
            val hours = remainingSeconds / 3600
            val minutes = (remainingSeconds % 3600) / 60
            val seconds = remainingSeconds % 60
            if (hours > 0) {
                "${hours}小时${minutes}分${seconds}秒"
            } else if (minutes > 0) {
                "${minutes}分${seconds}秒"
            } else {
                "${seconds}秒"
            }
        }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "定时管理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isWaitingForSongEnd) {
                    Text(
                        text = "定时时长已达到，将在当前歌曲播放完毕后自动停止播放。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = "当前定时倒计时剩余：$formattedRemaining",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "倒计时停止后播放完整歌曲才会自动停止",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    SleepTimerManager.cancelTimer()
                    Toast.makeText(context, "已关闭定时停止", Toast.LENGTH_SHORT).show()
                    onDismissRequest()
                },
            ) {
                Text("关闭定时", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onResetTimer()
                },
            ) {
                Text("重新设置")
            }
        },
    )
}
