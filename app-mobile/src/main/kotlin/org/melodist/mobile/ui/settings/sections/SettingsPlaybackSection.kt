package org.melodist.mobile.ui.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
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
        if (settings.enableUsbExclusive) {
            if (!activeUsbDevice.isNullOrBlank()) {
                "已连接: $activeUsbDevice"
            } else {
                "已开启 32-bit 浮点通道，连接 USB DAC 时优先硬件输出"
            }
        } else {
            "使用 32-bit 浮点通道输出，避免 16-bit 整数截断失真"
        }

    Column(modifier = modifier) {
        // 播放与音质分组
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
                title = "高解析度音频输出",
                subtitle = usbSubtitle,
                checked = settings.enableUsbExclusive,
                onCheckedChange = { AppSettingsManager.setEnableUsbExclusive(it) },
            )

            SettingsDivider()

            SettingsSwitchRow(
                icon = Icons.Rounded.Memory,
                title = "音频硬件卸载",
                subtitle =
                    if (settings.enableAudioOffload) {
                        "已启用独立 DSP 硬件解码，降低后台播放功耗"
                    } else {
                        "由 CPU/框架层解码输出，兼容完整音频处理"
                    },
                checked = settings.enableAudioOffload,
                onCheckedChange = { AppSettingsManager.setEnableAudioOffload(it) },
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
                            } catch (_: Exception) {
                            }
                        }
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 歌词与显示分组
        SettingsGroupTitle(title = "歌词与显示")
        SettingsGroupCard {
            SettingsSwitchRow(
                icon = Icons.Rounded.Translate,
                title = "双语歌词翻译",
                subtitle = "在外文歌曲播放界面显示中文对照翻译",
                checked = settings.showBilingualLyrics,
                onCheckedChange = { AppSettingsManager.setShowBilingualTranslation(it) },
            )

            SettingsDivider()

            SettingsSwitchRow(
                icon = Icons.Rounded.Lyrics,
                title = "逐字卡拉OK动效",
                subtitle = "支持词级别时间戳时平滑渲染字级变色动效",
                checked = settings.enableWordByWordAnim,
                onCheckedChange = { AppSettingsManager.setEnableWordByWordAnimation(it) },
            )

            SettingsDivider()

            SettingsClickableRow(
                icon = Icons.Rounded.Palette,
                title = "播放器歌词字号",
                subtitle = settings.lyricFontSize.label,
                onClick = { showFontSizeDialog = true },
            )
        }
    }

    if (showQualityDialog) {
        AudioQualityBottomSheet(
            currentTier = settings.preferredQualityTier,
            availableTiers = AudioQualityTier.entries.toSet(),
            title = "默认优先音质",
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
        AlertDialog(
            onDismissRequest = { showFontSizeDialog = false },
            title = { Text("选择歌词字号") },
            text = {
                Column {
                    LyricFontSize.entries.forEach { font ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        AppSettingsManager.setLyricFontSize(font)
                                        showFontSizeDialog = false
                                    }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.lyricFontSize == font,
                                onClick = {
                                    AppSettingsManager.setLyricFontSize(font)
                                    showFontSizeDialog = false
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${font.label} (${font.spValue}sp)",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFontSizeDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}
