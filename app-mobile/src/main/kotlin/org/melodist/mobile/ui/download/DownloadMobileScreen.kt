package org.melodist.mobile.ui.download

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.melodist.data.AppSettingsManager
import org.melodist.data.download.DownloadManager
import org.melodist.data.download.DownloadStatus
import org.melodist.data.download.DownloadTask
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.components.QualityTierBadge
import org.melodist.mobile.ui.components.SongListDeleteType
import java.io.File

enum class DownloadTab {
    Completed,
    Active,
}

@Composable
fun DownloadMobileScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(DownloadTab.Completed) }

    val activeTasks by DownloadManager.activeTasks.collectAsState()
    val completedTasks by DownloadManager.completedTasks.collectAsState()

    var taskToDelete by remember { mutableStateOf<DownloadTask?>(null) }
    var taskToShowDetail by remember { mutableStateOf<DownloadTask?>(null) }
    var showCancelAllDialog by remember { mutableStateOf(false) }

    // 监听全局 Toast 事件
    LaunchedEffect(Unit) {
        DownloadManager.toastEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedTab == DownloadTab.Completed,
                    onClick = { selectedTab = DownloadTab.Completed },
                    label = { Text("已下载 (${completedTasks.size})") },
                    leadingIcon = {
                        Icon(Icons.Rounded.DownloadDone, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    shape = RoundedCornerShape(8.dp),
                )

                FilterChip(
                    selected = selectedTab == DownloadTab.Active,
                    onClick = { selectedTab = DownloadTab.Active },
                    label = { Text("正在下载 (${activeTasks.size})") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Downloading, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    shape = RoundedCornerShape(8.dp),
                )
            }

            // 当前下载目录小胶囊
            Surface(
                onClick = {
                    val dir = AppSettingsManager.getEffectiveDownloadDirectory()
                    Toast.makeText(context, "下载目录:\n${dir.absolutePath}", Toast.LENGTH_LONG).show()
                },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "目录",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        ) {
            when (selectedTab) {
                DownloadTab.Completed -> {
                    if (completedTasks.isEmpty()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(contentPadding),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Rounded.DownloadDone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.size(56.dp),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "暂无已下载的歌曲",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        val songs =
                            completedTasks.map { task ->
                                task.song.copy(
                                    localFilePath = task.filePath,
                                    currentTier = task.tier,
                                    coverUrl = if (task.song.coverUrl.isNotBlank()) task.song.coverUrl else "",
                                )
                            }

                        CommonSongList(
                            songs = songs,
                            deleteType = SongListDeleteType.LocalFile,
                            enableDownload = false,
                            showQualityBadge = true,
                            contentPadding =
                                PaddingValues(
                                    top = 2.dp,
                                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                ),
                            headerItems = {
                                item(key = "completed_downloads_header") {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "共 ${completedTasks.size} 首歌曲",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )

                                        val totalBytes = completedTasks.sumOf { it.downloadedBytes }
                                        Text(
                                            text = "占用 ${formatDownloadBytes(totalBytes)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }

                DownloadTab.Active -> {
                    if (activeTasks.isEmpty()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(contentPadding),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Rounded.Downloading,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.size(56.dp),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "当前没有正在下载的任务",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            val totalSpeed = activeTasks.sumOf { it.speedBytesPerSec }
                            val isAnyDownloading = activeTasks.any { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Pending }
                            val isAnyPaused = activeTasks.any { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${activeTasks.size} 个任务",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (totalSpeed > 0L) {
                                        Text(
                                            text = " · ${formatDownloadSpeed(totalSpeed)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (isAnyPaused) {
                                        FilledTonalButton(
                                            onClick = { DownloadManager.resumeAll() },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(8.dp),
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("全部开始", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    if (isAnyDownloading) {
                                        OutlinedButton(
                                            onClick = { DownloadManager.pauseAll() },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(8.dp),
                                        ) {
                                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("全部暂停", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { showCancelAllDialog = true },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "全部删除",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }

                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding =
                                    PaddingValues(
                                        top = 2.dp,
                                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                    ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(activeTasks, key = { it.id }) { task ->
                                    ActiveDownloadTaskCard(
                                        task = task,
                                        onPause = { DownloadManager.pauseDownload(task.id) },
                                        onResume = { DownloadManager.resumeDownload(task.id) },
                                        onCancel = { DownloadManager.cancelDownload(task.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCancelAllDialog) {
        AlertDialog(
            onDismissRequest = { showCancelAllDialog = false },
            title = { Text("清空下载任务") },
            text = { Text("确定要取消并删除所有正在下载和排队中的任务吗？未完成的临时文件将被清理。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        DownloadManager.cancelAll()
                        showCancelAllDialog = false
                    },
                ) {
                    Text("确定删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAllDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}
