package org.melodist.mobile.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.melodist.data.update.AppUpdateDownloader
import org.melodist.data.update.UpdateChecker
import org.melodist.data.update.UpdateDownloadState
import org.melodist.data.update.UpdateResult
import java.util.Locale

@Composable
fun NewVersionDialog(
    newVersion: UpdateResult.NewVersion,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadState by remember { mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Idle) }

    fun startDownload(downloadUrl: String) {
        downloadJob?.cancel()
        downloadJob =
            coroutineScope.launch {
                AppUpdateDownloader.downloadApk(context, downloadUrl, newVersion.tagName)
                    .collect { state ->
                        downloadState = state
                        if (state is UpdateDownloadState.Completed) {
                            AppUpdateDownloader.installApk(context, state.apkFile)
                        }
                    }
            }
    }

    AlertDialog(
        onDismissRequest = {
            downloadJob?.cancel()
            onDismissRequest()
        },
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
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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

                when (val state = downloadState) {
                    is UpdateDownloadState.Downloading -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val downloadedMb = state.bytesDownloaded.toDouble() / (1024 * 1024)
                            val totalMb = state.totalBytes.toDouble() / (1024 * 1024)
                            val percent = (state.progress * 100).toInt()
                            Text(
                                text = "正在下载: %.1f MB / %.1f MB".format(Locale.CHINA, downloadedMb, totalMb),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                text = "$percent%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    is UpdateDownloadState.Completed -> {
                        Text(
                            text = "安装包已就绪，点击安装即可开始更新。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is UpdateDownloadState.Error -> {
                        Text(
                            text = "下载失败: ${state.message}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is UpdateDownloadState.Idle -> {}
                }
            }
        },
        confirmButton = {
            when (val state = downloadState) {
                is UpdateDownloadState.Completed -> {
                    Button(
                        onClick = {
                            AppUpdateDownloader.installApk(context, state.apkFile)
                        },
                    ) {
                        Text("立即安装")
                    }
                }
                is UpdateDownloadState.Downloading -> {
                    Button(
                        onClick = {},
                        enabled = false,
                    ) {
                        Text("下载中...")
                    }
                }
                is UpdateDownloadState.Error -> {
                    Button(
                        onClick = {
                            val url = newVersion.downloadUrl
                            if (!url.isNullOrBlank()) {
                                startDownload(url)
                            }
                        },
                    ) {
                        Text("重试下载")
                    }
                }
                is UpdateDownloadState.Idle -> {
                    Button(
                        onClick = {
                            val url = newVersion.downloadUrl
                            if (!url.isNullOrBlank()) {
                                startDownload(url)
                            } else {
                                val fallbackUrl = "${UpdateChecker.REPO_WEB_URL}/releases"
                                val intent =
                                    Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                try {
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                }
                                onDismissRequest()
                            }
                        },
                    ) {
                        Text(if (!newVersion.downloadUrl.isNullOrBlank()) "立即更新" else "前往网页下载")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    downloadJob?.cancel()
                    onDismissRequest()
                },
            ) {
                Text(if (downloadState is UpdateDownloadState.Downloading) "取消下载" else "稍后再说")
            }
        },
    )
}
