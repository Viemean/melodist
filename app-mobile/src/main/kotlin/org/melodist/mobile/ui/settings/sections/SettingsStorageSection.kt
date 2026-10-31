package org.melodist.mobile.ui.settings.sections

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.melodist.data.AppSettings
import org.melodist.data.AppSettingsManager
import org.melodist.data.CacheUsageDetail
import org.melodist.data.LyricSaveMode
import org.melodist.data.download.DownloadManager
import org.melodist.mobile.ui.components.SettingsClickableRow
import org.melodist.mobile.ui.components.SettingsGroupCard
import org.melodist.mobile.ui.components.SettingsGroupTitle
import org.melodist.mobile.ui.components.SettingsSwitchRow
import java.io.File

@Composable
fun SettingsStorageSection(
    settings: AppSettings = AppSettingsManager.settings.collectAsState().value,
    cacheUsage: CacheUsageDetail,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isCleaningCache by remember { mutableStateOf(false) }
    var showDownloadDirDialog by remember { mutableStateOf(false) }
    var showLyricSaveModeDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        SettingsGroupTitle(title = "下载与存储")
        SettingsGroupCard {
            SettingsClickableRow(
                icon = Icons.Rounded.Download,
                title = "歌曲下载目录",
                subtitle = AppSettingsManager.getEffectiveDownloadDirectory().absolutePath,
                onClick = { showDownloadDirDialog = true },
            )

            SettingsClickableRow(
                icon = Icons.Rounded.Subtitles,
                title = "歌词保存方式",
                subtitle = settings.lyricSaveMode.label,
                onClick = { showLyricSaveModeDialog = true },
            )

            SettingsSwitchRow(
                icon = Icons.Rounded.Translate,
                title = "保存歌词翻译",
                subtitle = "开启后内嵌和 LRC 会保存翻译",
                checked = settings.saveLyricTranslation,
                onCheckedChange = { AppSettingsManager.setSaveLyricTranslation(it) },
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "本地缓存占用",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "共 ${cacheUsage.totalFormatted} (封面 ${cacheUsage.imageFormatted} / 媒体 ${cacheUsage.mediaFormatted})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (cacheUsage.mediaQuotaBytes > 0L) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val percent = (cacheUsage.mediaUsageFraction * 100).toInt()
                        val trackDesc = if (cacheUsage.cachedTrackCount > 0) "，已缓存 ${cacheUsage.cachedTrackCount} 首歌曲" else ""
                        Text(
                            text = "音频配额: ${cacheUsage.mediaFormatted} / ${cacheUsage.mediaQuotaFormatted} ($percent%$trackDesc)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { cacheUsage.mediaUsageFraction },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                        )
                    }
                }
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            isCleaningCache = true
                            AppSettingsManager.clearAllCacheData()
                            AppSettingsManager.refreshCacheUsage(context)
                            isCleaningCache = false
                            Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isCleaningCache,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    if (isCleaningCache) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("清理")
                    }
                }
            }
        }
    }

    if (showDownloadDirDialog) {
        var inputPath by remember { mutableStateOf(AppSettingsManager.getEffectiveDownloadDirectory().absolutePath) }
        AlertDialog(
            onDismissRequest = { showDownloadDirDialog = false },
            title = { Text("设置歌曲下载目录") },
            text = {
                Column {
                    Text(
                        text = "下载的歌曲音频及内嵌原图、歌词将保存至该目录：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = inputPath,
                        onValueChange = { inputPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("下载路径") },
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                inputPath = AppSettingsManager.getDefaultDownloadDirectory()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Text("默认Music目录", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                inputPath = File(dlDir, "Melodist").absolutePath
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Text("标准Download目录", fontSize = 11.sp)
                        }
                    }

                    val isPermissionMissing =
                        remember {
                            !DownloadManager.hasStoragePermission(context)
                        }
                    if (isPermissionMissing) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "保存到公共目录需授予“所有文件访问权限”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FilledTonalButton(
                            onClick = {
                                DownloadManager.requestStoragePermission(context)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            Text("前往设置授予所有文件访问权限", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = inputPath.trim()
                    if (trimmed.isNotBlank()) {
                        AppSettingsManager.setDownloadDirectory(trimmed)
                        Toast.makeText(context, "下载目录已更新", Toast.LENGTH_SHORT).show()
                    }
                    showDownloadDirDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDirDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showLyricSaveModeDialog) {
        AlertDialog(
            onDismissRequest = { showLyricSaveModeDialog = false },
            title = { Text("歌词保存方式") },
            text = {
                Column {
                    LyricSaveMode.entries.forEach { mode ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        AppSettingsManager.setLyricSaveMode(mode)
                                        showLyricSaveModeDialog = false
                                    }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.lyricSaveMode == mode,
                                onClick = {
                                    AppSettingsManager.setLyricSaveMode(mode)
                                    showLyricSaveModeDialog = false
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLyricSaveModeDialog = false }) {
                    Text("完成")
                }
            },
        )
    }
}
