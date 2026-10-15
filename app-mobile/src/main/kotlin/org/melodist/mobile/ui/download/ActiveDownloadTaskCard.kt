package org.melodist.mobile.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.melodist.data.download.DownloadStatus
import org.melodist.data.download.DownloadTask
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.components.QualityTierBadge

@Composable
internal fun ActiveDownloadTaskCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
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
                    DownloadStatus.Downloading, DownloadStatus.Pending -> {
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusText =
                    when (task.status) {
                        DownloadStatus.Pending -> {
                            "等待下载..."
                        }

                        DownloadStatus.Downloading -> {
                            if (task.speedBytesPerSec > 0L) {
                                "正在下载 · ${formatDownloadSpeed(task.speedBytesPerSec)}"
                            } else {
                                "正在连接直链..."
                            }
                        }

                        DownloadStatus.Paused -> {
                            "已暂停"
                        }

                        DownloadStatus.Failed -> {
                            task.errorMessage ?: "下载失败"
                        }

                        DownloadStatus.Completed -> {
                            "下载完成"
                        }
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
                        text = "${formatDownloadBytes(task.downloadedBytes)} / ${formatDownloadBytes(task.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

internal fun formatDownloadBytes(bytes: Long): String {
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

internal fun formatDownloadSpeed(bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) "%.1f MB/s".format(mb) else "%.0f KB/s".format(kb)
}
