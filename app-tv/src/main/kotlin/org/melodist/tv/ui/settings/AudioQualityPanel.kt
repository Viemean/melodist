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
import org.melodist.model.AudioQualityTier
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AudioQualityPanel(menuRequester: FocusRequester) {
    val settings by AppSettingsManager.settings.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingPanelHeader(title = "音频与音质设置")

        // 音质等级网格
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "首选音质等级",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )

            val row1 =
                listOf(
                    AudioQualityTier.Master to "Master (192k/24b)",
                    AudioQualityTier.HiRes to "Hi-Res (96k/24b)",
                    AudioQualityTier.SQ to "SQ 无损 (FLAC)",
                )
            val row2 =
                listOf(
                    AudioQualityTier.HQ to "HQ 高品 (320k)",
                    AudioQualityTier.Standard to "标准 (128k)",
                )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 第一行：无损与母带层级 (依次左移，首列返回导航栏)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row1.forEachIndexed { index, (tier, label) ->
                        val isSelected = (settings.preferredQualityTier == tier)
                        val btnModifier =
                            Modifier
                                .weight(1f)
                                .then(if (index == 0) Modifier.focusProperties { left = menuRequester } else Modifier)

                        Button(
                            onClick = {
                                PlaybackManager.setPreferredQualityTier(tier)
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
                            scale = ButtonDefaults.scale(focusedScale = 1.04f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // 第二行：高品与标准层级 (依次左移，首列返回导航栏)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row2.forEachIndexed { index, (tier, label) ->
                        val isSelected = (settings.preferredQualityTier == tier)
                        val btnModifier =
                            Modifier
                                .weight(1f)
                                .then(if (index == 0) Modifier.focusProperties { left = menuRequester } else Modifier)

                        Button(
                            onClick = {
                                PlaybackManager.setPreferredQualityTier(tier)
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
                            scale = ButtonDefaults.scale(focusedScale = 1.04f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    // 占位列，使第二行按钮与上方完全等宽对齐
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // 2. 音频直通 (Bitstream Passthrough)
        SettingSwitchCard(
            title = "音频直通",
            checked = settings.enableAudioPassthrough,
            menuRequester = menuRequester,
            onToggle = {
                AppSettingsManager.updateAudioPassthrough(!settings.enableAudioPassthrough)
            },
        )

        // 3. 音频硬件卸载 (Audio Offload)
        SettingSwitchCard(
            title = "音频硬件卸载",
            checked = settings.enableAudioOffload,
            menuRequester = menuRequester,
            onToggle = {
                AppSettingsManager.updateAudioOffload(!settings.enableAudioOffload)
            },
        )

        // 4. 本地与 WebDAV 智能歌词匹配
        SettingSwitchCard(
            title = "本地与 WebDAV 音乐切片匹配歌词",
            checked = settings.enableAutoMatchLyrics,
            menuRequester = menuRequester,
            onToggle = {
                AppSettingsManager.updateAutoMatchLyrics(!settings.enableAutoMatchLyrics)
            },
        )
    }
}
