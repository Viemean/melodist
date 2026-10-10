package org.melodist.mobile.ui.settings.sections

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.melodist.data.AppSettings
import org.melodist.data.AppSettingsManager
import org.melodist.data.LyricFontSize
import org.melodist.mobile.ui.components.AudioQualityBottomSheet
import org.melodist.mobile.ui.components.SettingsClickableRow
import org.melodist.mobile.ui.components.SettingsDivider
import org.melodist.mobile.ui.components.SettingsGroupCard
import org.melodist.mobile.ui.components.SettingsGroupTitle
import org.melodist.mobile.ui.components.SettingsSwitchRow
import org.melodist.mobile.ui.components.getQualityTierDetailedLabel
import org.melodist.model.AudioQualityTier
import org.melodist.playback.PlaybackManager

@Composable
fun SettingsPlaybackSection(
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    var showQualityDialog by remember { mutableStateOf(false) }
    var showCellularQualityDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }

    val activeUsbDevice by PlaybackManager.activeUsbDeviceName.collectAsState()
    val usbSubtitle =
        if (settings.enableUsbExclusive && !activeUsbDevice.isNullOrBlank()) {
            "已连接: $activeUsbDevice"
        } else {
            "以 32-bit 浮点处理输出，实际听感可能不会提升"
        }

    Column(modifier = modifier) {
        SettingsGroupTitle(title = "播放与音质")
        SettingsGroupCard {
            SettingsClickableRow(
                icon = Icons.Rounded.HighQuality,
                title = "默认优先音质",
                subtitle = getQualityTierDetailedLabel(settings.preferredQualityTier),
                onClick = { showQualityDialog = true },
            )

            SettingsDivider()

            SettingsClickableRow(
                icon = Icons.Rounded.SignalCellularAlt,
                title = "移动网络音质",
                subtitle = getQualityTierDetailedLabel(settings.cellularQualityTier),
                onClick = { showCellularQualityDialog = true },
            )

            SettingsDivider()

            SettingsSwitchRow(
                icon = Icons.Rounded.Usb,
                title = "32Bit浮点通道输出",
                subtitle = usbSubtitle,
                checked = settings.enableUsbExclusive,
                onCheckedChange = { AppSettingsManager.setEnableUsbExclusive(it) },
            )

            SettingsDivider()

            val context = androidx.compose.ui.platform.LocalContext.current
            var isBatteryWhitelisted by remember {
                val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                mutableStateOf(pm?.isIgnoringBatteryOptimizations(context.packageName) == true)
            }
            SettingsClickableRow(
                icon = Icons.Rounded.BatteryChargingFull,
                title = "后台优化",
                subtitle =
                    if (isBatteryWhitelisted) {
                        "已加入电池优化白名单"
                    } else {
                        "未加入电池优化白名单"
                    },
                onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        try {
                            val intent =
                                if (!isBatteryWhitelisted) {
                                    android.content
                                        .Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .apply {
                                            data = android.net.Uri.parse("package:${context.packageName}")
                                        }
                                } else {
                                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                            } catch (e: Exception) {
                                Log.w("SettingsPlaybackSection", "Operation failed", e)
                            }
                        }
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 歌词分组
        SettingsGroupTitle(title = "歌词")
        SettingsGroupCard {
            SettingsSwitchRow(
                icon = Icons.Rounded.Translate,
                title = "歌词翻译",
                subtitle = "显示中文翻译",
                checked = settings.showBilingualLyrics,
                onCheckedChange = { AppSettingsManager.setShowBilingualTranslation(it) },
            )

            SettingsDivider()

            SettingsSwitchRow(
                icon = Icons.Rounded.Sync,
                title = "自动匹配歌词",
                subtitle = "为不完整歌词的本地和 WebDAV 音乐匹配歌词",
                checked = settings.enableAutoMatchLyrics,
                onCheckedChange = { AppSettingsManager.updateAutoMatchLyrics(it) },
            )

            if (settings.enableAutoMatchLyrics) {
                SettingsDivider()

                SettingsSwitchRow(
                    icon = Icons.Rounded.MusicNote,
                    title = "忽略内嵌歌词",
                    subtitle = "优先使用匹配的歌词而不是内嵌歌词",
                    checked = settings.ignoreEmbeddedLyrics,
                    onCheckedChange = { AppSettingsManager.updateIgnoreEmbeddedLyrics(it) },
                )
            }

            SettingsDivider()

            SettingsClickableRow(
                icon = Icons.Rounded.Palette,
                title = "歌词字号",
                subtitle =
                    if (settings.lyricFontSize == LyricFontSize.Custom) {
                        "自定义 (${settings.customLyricFontSizeSp}sp)"
                    } else {
                        "${settings.lyricFontSize.label} (${settings.effectiveLyricTitleSp}sp)"
                    },
                onClick = { showFontSizeDialog = true },
            )
        }
    }

    if (showQualityDialog) {
        AudioQualityBottomSheet(
            currentTier = settings.preferredQualityTier,
            availableTiers = AudioQualityTier.entries.toSet(),
            title = "默认选择音质",
            enforceCellularRestriction = false,
            showSubtitle = false,
            onSelectTier = { tier ->
                AppSettingsManager.setPreferredQualityTier(tier)
                PlaybackManager.setPreferredQualityTier(tier)
            },
            onDismissRequest = { showQualityDialog = false },
        )
    }

    if (showCellularQualityDialog) {
        AudioQualityBottomSheet(
            currentTier = settings.cellularQualityTier,
            availableTiers = AudioQualityTier.entries.toSet(),
            title = "移动网络音质上限",
            enforceCellularRestriction = false,
            showSubtitle = false,
            onSelectTier = { tier ->
                AppSettingsManager.setCellularQualityTier(tier)
            },
            onDismissRequest = { showCellularQualityDialog = false },
        )
    }

    if (showFontSizeDialog) {
        var selectedFont by remember { mutableStateOf(settings.lyricFontSize) }
        var customSp by remember { mutableStateOf(settings.customLyricFontSizeSp) }

        val previewTitleSp =
            if (selectedFont == LyricFontSize.Custom) {
                customSp
            } else {
                selectedFont.titleSp
            }
        val previewSubSp =
            if (selectedFont == LyricFontSize.Custom) {
                (customSp * 0.68f).toInt().coerceAtLeast(10)
            } else {
                selectedFont.subSp
            }

        AlertDialog(
            onDismissRequest = { showFontSizeDialog = false },
            title = { Text("选择歌词字号") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LyricFontSize.entries.forEach { font ->
                        val labelText =
                            if (font == LyricFontSize.Custom) {
                                "${font.label} (${customSp}sp)"
                            } else {
                                "${font.label} (${font.spValue}sp)"
                            }
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedFont = font
                                        if (font != LyricFontSize.Custom) {
                                            AppSettingsManager.setLyricFontSize(font)
                                        } else {
                                            AppSettingsManager.setCustomLyricFontSize(customSp)
                                        }
                                    }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedFont == font,
                                onClick = {
                                    selectedFont = font
                                    if (font != LyricFontSize.Custom) {
                                        AppSettingsManager.setLyricFontSize(font)
                                    } else {
                                        AppSettingsManager.setCustomLyricFontSize(customSp)
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = labelText,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    if (selectedFont == LyricFontSize.Custom) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "字号微调: ${customSp}sp",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (customSp > 14) {
                                            customSp -= 1
                                            AppSettingsManager.setCustomLyricFontSize(customSp)
                                        }
                                    },
                                    enabled = customSp > 14,
                                ) {
                                    Icon(Icons.Rounded.Remove, contentDescription = "减小字号")
                                }
                                IconButton(
                                    onClick = {
                                        if (customSp < 48) {
                                            customSp += 1
                                            AppSettingsManager.setCustomLyricFontSize(customSp)
                                        }
                                    },
                                    enabled = customSp < 48,
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = "增大字号")
                                }
                            }
                        }

                        Slider(
                            value = customSp.toFloat(),
                            onValueChange = {
                                customSp = it.toInt().coerceIn(14, 48)
                                AppSettingsManager.setCustomLyricFontSize(customSp)
                            },
                            valueRange = 14f..48f,
                            steps = 33,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 实时预览卡片
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "歌词效果实时预览",
                                fontSize = previewTitleSp.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Lyrics Preview Translation",
                                fontSize = previewSubSp.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFontSizeDialog = false }) {
                    Text("完成")
                }
            },
        )
    }
}
