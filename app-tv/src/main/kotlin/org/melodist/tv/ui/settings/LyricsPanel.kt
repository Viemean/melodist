package org.melodist.tv.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import org.melodist.data.AppSettingsManager
import org.melodist.data.LyricFontSize
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LyricsPanel(menuRequester: FocusRequester) {
    val settings by AppSettingsManager.settings.collectAsState()
    val scrollState = rememberScrollState()
    var showCustomSizeDialog by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingPanelHeader(title = "播放与歌词设置")

        // 1. 默认双语逐行翻译
        SettingSwitchCard(
            title = "显示双语翻译",
            checked = settings.showBilingualLyrics,
            menuRequester = menuRequester,
            onToggle = {
                AppSettingsManager.updateShowBilingualLyrics(!settings.showBilingualLyrics)
            },
        )

        // 2. 歌词字号缩放 (依次左移，首列返回导航栏)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "歌词字号大小",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LyricFontSize.entries.forEachIndexed { index, size ->
                    val isSelected = (settings.lyricFontSize == size)
                    val btnModifier =
                        Modifier
                            .weight(1f)
                            .then(if (index == 0) Modifier.focusProperties { left = menuRequester } else Modifier)

                    Button(
                        onClick = {
                            if (size == LyricFontSize.Custom) {
                                AppSettingsManager.updateLyricFontSize(size)
                                showCustomSizeDialog = true
                            } else {
                                AppSettingsManager.updateLyricFontSize(size)
                            }
                        },
                        modifier = btnModifier,
                        shape =
                            ButtonDefaults.shape(
                                shape = MelodistShapes.ButtonCorner,
                                focusedShape = MelodistShapes.ButtonCorner,
                            ),
                        colors =
                            ButtonDefaults.colors(
                                containerColor =
                                    if (isSelected) {
                                        MelodistColors.AccentGreen.copy(
                                            alpha = 0.25f,
                                        )
                                    } else {
                                        Color.White.copy(alpha = 0.05f)
                                    },
                                focusedContainerColor = Color.White,
                                contentColor = if (isSelected) MelodistColors.AccentGreen else Color.White,
                                focusedContentColor = Color.Black,
                            ),
                        border =
                            ButtonDefaults.border(
                                border =
                                    Border(
                                        border =
                                            BorderStroke(
                                                1.dp,
                                                if (isSelected) {
                                                    MelodistColors.AccentGreen.copy(
                                                        alpha = 0.7f,
                                                    )
                                                } else {
                                                    Color.White.copy(alpha = 0.12f)
                                                },
                                            ),
                                        shape = MelodistShapes.ButtonCorner,
                                    ),
                                focusedBorder =
                                    Border(
                                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                        shape = MelodistShapes.ButtonCorner,
                                    ),
                            ),
                        scale = ButtonDefaults.scale(focusedScale = 1.05f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = size.label,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val spText =
                                if (size == LyricFontSize.Custom) {
                                    "(${settings.customLyricFontSizeSp}sp)"
                                } else {
                                    "(${size.spValue}sp)"
                                }
                            Text(
                                text = spText,
                                fontSize = 12.sp,
                                color = LocalContentColor.current.copy(alpha = 0.75f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "示例歌词英语",
                    fontSize = settings.effectiveLyricTitleSp.sp,
                    fontWeight = FontWeight.Bold,
                    color = MelodistColors.AccentGreen,
                    textAlign = TextAlign.Center,
                    lineHeight = (settings.effectiveLyricTitleSp * 1.42f).sp,
                )
                if (settings.showBilingualLyrics) {
                    Text(
                        text = "示例翻译",
                        fontSize = settings.effectiveLyricSubSp.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.70f),
                        textAlign = TextAlign.Center,
                        lineHeight = (settings.effectiveLyricTitleSp * 1.0f).sp,
                    )
                }
            }
        }
    }

    if (showCustomSizeDialog) {
        CustomLyricFontSizeDialog(
            initialValue = settings.customLyricFontSizeSp,
            onDismissRequest = { showCustomSizeDialog = false },
            onConfirm = { newSize ->
                AppSettingsManager.updateCustomLyricFontSize(newSize)
            },
        )
    }
}

@Composable
private fun CustomLyricFontSizeDialog(
    initialValue: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var textValue by remember { mutableStateOf(initialValue.toString()) }
    val focusRequester = remember { FocusRequester() }

    fun submitValue() {
        val parsed = textValue.toIntOrNull() ?: 16
        val clamped = parsed.coerceIn(16, 40)
        onConfirm(clamped)
        onDismissRequest()
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .width(420.dp)
                    .clip(MelodistShapes.DialogCorner)
                    .background(Color(0xFF1E1E24))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), MelodistShapes.DialogCorner)
                    .padding(24.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "自定义歌词字号",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )

                Text(
                    text = "请输入字号大小（16 - 40 sp），小于 16 自动设为 16，大于 40 自动设为 40",
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
                            if (digits.length <= 2) {
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
                            text = "例如 30",
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
