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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.melodist.data.AppSettingsManager
import org.melodist.data.download.DownloadManager
import org.melodist.data.download.DownloadStatus
import org.melodist.data.download.DownloadTask
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.components.SongListDeleteType
import org.melodist.model.AudioQualityTier
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

    // 监听全局 Toast 事件
    LaunchedEffect(Unit) {
        DownloadManager.toastEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部控制栏：分段药丸与下载目录快捷提示
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

        // 主内容区域
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
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "在歌曲菜单中点击“下载”，畅享高清原声与双语歌词",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    } else {
                        val songs =
                            completedTasks.map { task ->
                                val localFile = File(task.filePath)
                                task.song.copy(
                                    localFilePath = task.filePath,
                                    coverUrl = if (task.song.coverUrl.isNotBlank()) task.song.coverUrl else "",
                                )
                            }

                        CommonSongList(
                            songs = songs,
                            deleteType = SongListDeleteType.LocalFile,
                            enableDownload = false,
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
                                            text = "占用 ${formatBytes(totalBytes)}",
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
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding =
                                PaddingValues(
                                    top = 6.dp,
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

@Composable
private fun ActiveDownloadTaskCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtImage(
                    coverUrl = task.song.thumbnailCoverUrl,
                    contentDescription = task.song.name,
                    shape = RoundedCornerShape(8.dp),
                    placeholderIconSize = 22.dp,
                    modifier = Modifier.size(44.dp),
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.song.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        QualityTierBadge(tier = task.tier)
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = task.song.singer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 控制按钮
                when (task.status) {
                    DownloadStatus.Downloading -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Rounded.Pause, contentDescription = "暂停", modifier = Modifier.size(20.dp))
                        }
                    }
                    DownloadStatus.Paused, DownloadStatus.Failed -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "继续", modifier = Modifier.size(20.dp))
                        }
                    }
                    else -> {}
                }

                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "取消",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 进度条
            if (task.status == DownloadStatus.Downloading && task.totalBytes <= 0L) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
            } else {
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 状态描述与下载速度
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusText =
                    when (task.status) {
                        DownloadStatus.Pending -> "等待下载..."
                        DownloadStatus.Downloading -> {
                            if (task.speedBytesPerSec > 0L) {
                                "正在下载 · ${formatSpeed(task.speedBytesPerSec)}"
                            } else {
                                "正在连接直链..."
                            }
                        }
                        DownloadStatus.Paused -> "已暂停"
                        DownloadStatus.Failed -> task.errorMessage ?: "下载失败"
                        DownloadStatus.Completed -> "下载完成"
                    }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (task.status == DownloadStatus.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )

                if (task.totalBytes > 0L) {
                    Text(
                        text = "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityTierBadge(tier: AudioQualityTier) {
    val (label, bg, fg) =
        when (tier) {
            AudioQualityTier.HiRes -> Triple("Hi-Res", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
            AudioQualityTier.Master -> Triple("Master", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
            AudioQualityTier.SQ -> Triple("SQ", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            AudioQualityTier.HQ -> Triple("HQ", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Triple("标准", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        }

    Surface(
        color = bg,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.1f KB".format(kb)
        else -> "$bytes B"
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) "%.1f MB/s".format(mb) else "%.0f KB/s".format(kb)
}
