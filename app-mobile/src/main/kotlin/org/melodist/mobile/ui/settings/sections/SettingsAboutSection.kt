package org.melodist.mobile.ui.settings.sections

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.melodist.api.PlaybackCredentials
import org.melodist.data.update.UpdateChecker
import org.melodist.data.update.UpdateResult
import org.melodist.mobile.BuildConfig
import org.melodist.mobile.ui.components.SettingsGroupCard
import org.melodist.mobile.ui.components.SettingsGroupTitle

@Composable
fun SettingsAboutSection(
    playbackCreds: PlaybackCredentials?,
    onTriggerCredsDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var versionClickCount by remember { mutableIntStateOf(0) }
    var lastVersionClickTime by remember { mutableLongStateOf(0L) }

    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var newVersionDialogResult by remember { mutableStateOf<UpdateResult.NewVersion?>(null) }

    Column(modifier = modifier) {
        SettingsGroupTitle(title = "关于")
        SettingsGroupCard {
            // 1. 应用与版本详情行（保留连击凭证彩蛋）
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val now = System.currentTimeMillis()
                            if (now - lastVersionClickTime > 2500L) {
                                versionClickCount = 1
                            } else {
                                versionClickCount++
                            }
                            lastVersionClickTime = now
                            if (versionClickCount in 7..9) {
                                Toast
                                    .makeText(
                                        context,
                                        "再点击 ${10 - versionClickCount} 次开启凭据管理",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            } else if (versionClickCount >= 10) {
                                versionClickCount = 0
                                onTriggerCredsDialog()
                            }
                        }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Melodist Mobile",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (playbackCreds != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "已启用独立播放凭证: ${playbackCreds.nick.ifBlank { playbackCreds.uin }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )

            // 2. 检查更新行
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isCheckingUpdate) {
                            isCheckingUpdate = true
                            updateStatusText = "正在检查更新..."
                            coroutineScope.launch {
                                val targetKw = if (context.packageName == "com.tencent.qqmusic") "originos" else "mobile"
                                val result =
                                    UpdateChecker.checkUpdate(
                                        currentVersion = BuildConfig.MELODIST_VERSION_NAME,
                                        targetKeyword = targetKw,
                                    )
                                isCheckingUpdate = false
                                when (result) {
                                    is UpdateResult.NewVersion -> {
                                        updateStatusText = "发现新版本 ${result.tagName}"
                                        newVersionDialogResult = result
                                    }
                                    is UpdateResult.Latest -> {
                                        updateStatusText = "已是最新版本"
                                        Toast
                                            .makeText(
                                                context,
                                                "当前已是最新版本 (v${result.currentVersion})",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                    is UpdateResult.Error -> {
                                        updateStatusText = "检查失败"
                                        Toast
                                            .makeText(
                                                context,
                                                "检查更新失败: ${result.message}",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                }
                            }
                        }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdateAlt,
                    contentDescription = "检查更新",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "检查更新",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (updateStatusText != null) {
                    Text(
                        text = updateStatusText.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (newVersionDialogResult != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )

            // 3. 开源仓库链接行
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val intent =
                                Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.REPO_WEB_URL)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            context.startActivity(intent)
                        }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.OpenInBrowser,
                    contentDescription = "开源仓库",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "开源仓库",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "github.com/Viemean/melodist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    // 发现新版本弹窗
    val newVersion = newVersionDialogResult
    if (newVersion != null) {
        AlertDialog(
            onDismissRequest = { newVersionDialogResult = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdateAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = {
                Text(
                    text = "发现新版本 ${newVersion.tagName}",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "更新日志:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = newVersion.releaseNotes.ifBlank { "本次更新包含功能优化与问题修复。" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val downloadUrl = newVersion.downloadUrl ?: "${UpdateChecker.REPO_WEB_URL}/releases"
                        val intent =
                            Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        context.startActivity(intent)
                        newVersionDialogResult = null
                    },
                ) {
                    Text("前往更新")
                }
            },
            dismissButton = {
                TextButton(onClick = { newVersionDialogResult = null }) {
                    Text("稍后再说")
                }
            },
        )
    }
}

