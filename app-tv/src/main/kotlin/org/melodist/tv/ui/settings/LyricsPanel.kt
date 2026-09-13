package org.melodist.tv.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            title = "默认显示双语逐行翻译",
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
                text = "歌词显示字号大小",
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
                            AppSettingsManager.updateLyricFontSize(size)
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
                        Text(
                            text = "${size.label} (${size.spValue}sp)",
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
