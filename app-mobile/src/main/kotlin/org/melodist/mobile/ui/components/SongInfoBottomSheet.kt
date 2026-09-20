package org.melodist.mobile.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.melodist.data.download.DownloadManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import org.melodist.playback.AudioAuditResult
import org.melodist.playback.AudioQualityAuditor
import org.melodist.playback.AudioQualityVerdict
import org.melodist.playback.PlaybackManager
import java.io.File

// 柔和的非刺眼主题色定义
private val AmberWarningText = Color(0xFFE5A93B)
private val GreenSuccessText = Color(0xFF66BB6A)
private val BlueInfoText = Color(0xFF64B5F6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongInfoBottomSheet(
    song: Song,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentPlayingSong by PlaybackManager.currentSong.collectAsState()
    val isCurrentlyPlaying = currentPlayingSong?.songMid == song.songMid
    val currentPlayingTier by PlaybackManager.currentTier.collectAsState()
    val effectiveTier = if (isCurrentlyPlaying) currentPlayingTier else song.currentTier
    val currentTrackSpec by PlaybackManager.currentTrackSpec.collectAsState()
    val probedOptions by PlaybackManager.probedQualityOptions.collectAsState()

    val localFilePath =
        remember(song) {
            val directPath = song.localFilePath?.takeIf { it.isNotBlank() && File(it).exists() }
            if (directPath != null) {
                directPath
            } else if (song.songMid.startsWith("webdav_")) {
                val server =
                    org.melodist.data.WebDavManager
                        .getActiveServer()
                val relativeHref = song.mediaMid.ifBlank { song.localFilePath ?: "" }
                if (server != null && relativeHref.isNotBlank()) {
                    org.melodist.data.WebDavManager
                        .getLocalCacheFile(server.id, relativeHref)
                        .takeIf { it.exists() && it.length() > 0L }
                        ?.absolutePath
                } else {
                    null
                }
            } else {
                DownloadManager.completedTasks.value.find { it.song.songMid == song.songMid }?.filePath?.takeIf {
                    File(it).exists()
                }
            }
        }

    var auditResult by remember { mutableStateOf<AudioAuditResult?>(null) }
    var isAuditing by remember { mutableStateOf(false) }

    fun runAudit(forceRefresh: Boolean = false) {
        if (isAuditing) return
        val targetSong = song.copy(currentTier = effectiveTier)
        if (forceRefresh) {
            AudioQualityAuditor.invalidateCache(targetSong.songMid)
        }
        isAuditing = true
        coroutineScope.launch {
            try {
                val result = AudioQualityAuditor.auditSong(context, targetSong, localFilePath)
                auditResult = result
            } finally {
                isAuditing = false
            }
        }
    }

    LaunchedEffect(song.songMid, effectiveTier) {
        runAudit(forceRefresh = false)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // 顶部标题与歌曲基础概要
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtImage(
                    coverUrl = song.thumbnailCoverUrl,
                    contentDescription = song.name,
                    shape = RoundedCornerShape(12.dp),
                    elevation = 4.dp,
                    placeholderIconSize = 28.dp,
                    modifier = Modifier.size(56.dp),
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${song.singer.ifBlank { "未知歌手" }} · ${song.album.ifBlank { "未知专辑" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 假音质检测与频谱分析卡片
            AudioAuditCard(
                auditResult = auditResult,
                isAuditing = isAuditing,
                currentTier = effectiveTier,
                onRefreshAudit = { runAudit(forceRefresh = true) },
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 音频技术参数卡片
            val matchedProbed =
                if (isCurrentlyPlaying) {
                    probedOptions.find { it.tier == effectiveTier }
                } else {
                    probedOptions.find { it.tier == song.currentTier }
                }

            val formatDisplay =
                when {
                    isCurrentlyPlaying && !currentTrackSpec?.format.isNullOrBlank() -> currentTrackSpec?.format?.uppercase() ?: ""
                    matchedProbed != null && matchedProbed.format.isNotBlank() -> matchedProbed.format.uppercase()
                    effectiveTier == AudioQualityTier.HiRes || effectiveTier == AudioQualityTier.Master -> "FLAC"
                    effectiveTier == AudioQualityTier.SQ -> "FLAC"
                    effectiveTier == AudioQualityTier.HQ -> "MP3"
                    else -> "AAC / MP3"
                }

            val sampleRateDisplay =
                when {
                    isCurrentlyPlaying && (currentTrackSpec?.sampleRateHz ?: 0) > 0 -> "${((currentTrackSpec?.sampleRateHz ?: 44100) / 1000.0).format(1)} kHz"
                    matchedProbed?.sampleRateHz != null && matchedProbed.sampleRateHz > 0 -> "${(matchedProbed.sampleRateHz / 1000.0).format(1)} kHz"
                    auditResult?.sampleRateHz != null && auditResult!!.sampleRateHz > 0 -> "${(auditResult!!.sampleRateHz / 1000.0).format(1)} kHz"
                    effectiveTier == AudioQualityTier.HiRes || effectiveTier == AudioQualityTier.Master -> "96.0 kHz"
                    else -> "44.1 kHz"
                }

            val bitDepthDisplay =
                when {
                    isCurrentlyPlaying && (currentTrackSpec?.bitDepth ?: 0) > 0 -> "${currentTrackSpec?.bitDepth}-bit"
                    matchedProbed?.bitDepth != null && matchedProbed.bitDepth > 0 -> "${matchedProbed.bitDepth}-bit"
                    effectiveTier == AudioQualityTier.HiRes || effectiveTier == AudioQualityTier.Master -> "24-bit"
                    auditResult?.bitDepth != null && auditResult!!.bitDepth > 0 -> "${auditResult!!.bitDepth}-bit"
                    else -> "16-bit"
                }

            val bitrateDisplay =
                when {
                    isCurrentlyPlaying && (currentTrackSpec?.bitrateKbps ?: 0) > 0 -> "${currentTrackSpec?.bitrateKbps} kbps"
                    matchedProbed != null && matchedProbed.bitrate.isNotBlank() -> matchedProbed.bitrate
                    auditResult?.bitrateKbps != null && auditResult!!.bitrateKbps > 0 -> "${auditResult!!.bitrateKbps} kbps"
                    effectiveTier == AudioQualityTier.Master -> "约 4500 kbps"
                    effectiveTier == AudioQualityTier.HiRes -> "约 2800 kbps"
                    effectiveTier == AudioQualityTier.SQ -> "约 850 kbps"
                    effectiveTier == AudioQualityTier.HQ -> "320 kbps"
                    else -> "128 kbps"
                }

            val fileSizeDisplay =
                when {
                    localFilePath != null -> formatFileSize(File(localFilePath).length())
                    matchedProbed?.sizeBytes != null && matchedProbed.sizeBytes > 0 -> formatFileSize(matchedProbed.sizeBytes)
                    else -> "在线流媒体（动态加载）"
                }

            val channelDisplay =
                when (auditResult?.channels) {
                    1 -> "单声道 (1.0 ch)"
                    6 -> "5.1 环绕声 (6 ch)"
                    8 -> "7.1 全景声 (8 ch)"
                    else -> "立体声 (2.0 ch)"
                }

            TechSpecSection(
                items =
                    listOf(
                        "编码格式" to formatDisplay,
                        "采样率" to sampleRateDisplay,
                        "采样位深" to bitDepthDisplay,
                        "声道配置" to channelDisplay,
                        "比特率" to bitrateDisplay,
                        "文件大小" to fileSizeDisplay,
                        "音质级别" to effectiveTier.name,
                    ),
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 歌曲标识符元数据卡片
            MetadataSection(
                song = song,
                localFilePath = localFilePath,
                context = context,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 底部一键复制全部参数按钮
            Button(
                onClick = {
                    val fullInfo =
                        buildString {
                            appendLine("【歌曲信息】")
                            appendLine("歌名: ${song.name}")
                            appendLine("歌手: ${song.singer}")
                            appendLine("专辑: ${song.album}")
                            appendLine("音质级别: ${effectiveTier.name}")
                            appendLine("编码格式: $formatDisplay")
                            appendLine("采样率: $sampleRateDisplay")
                            appendLine("位深: $bitDepthDisplay")
                            appendLine("声道: $channelDisplay")
                            appendLine("比特率: $bitrateDisplay")
                            appendLine("文件大小: $fileSizeDisplay")
                            if (auditResult != null) {
                                appendLine("真伪审计: ${auditResult?.description} (${auditResult?.details})")
                            }
                            appendLine("Song MID: ${song.songMid}")
                            if (song.albumMid.isNotBlank()) appendLine("Album MID: ${song.albumMid}")
                            if (song.effectiveMediaMid.isNotBlank()) appendLine("Media MID: ${song.effectiveMediaMid}")
                            if (!localFilePath.isNullOrBlank()) appendLine("本地路径: $localFilePath")
                        }
                    copyToClipboard(context, "歌曲详细信息", fullInfo)
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "复制全部技术信息",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AudioAuditCard(
    auditResult: AudioAuditResult?,
    isAuditing: Boolean,
    currentTier: AudioQualityTier,
    onRefreshAudit: () -> Unit,
) {
    val isLossyTier = currentTier == AudioQualityTier.Standard || currentTier == AudioQualityTier.HQ

    val (accentColor, badgeIcon, badgeTitle) =
        when {
            isAuditing -> {
                Triple(
                    MaterialTheme.colorScheme.primary,
                    Icons.Rounded.GraphicEq,
                    "正在分析音频频谱...",
                )
            }
            auditResult?.verdict == AudioQualityVerdict.FAKE_LOSSLESS || auditResult?.verdict == AudioQualityVerdict.UPSAMPLED_HIRES -> {
                Triple(
                    AmberWarningText,
                    Icons.Rounded.WarningAmber,
                    if (auditResult.verdict == AudioQualityVerdict.UPSAMPLED_HIRES) "疑似升频" else "疑似假无损",
                )
            }
            auditResult?.verdict == AudioQualityVerdict.AUTHENTIC -> {
                Triple(
                    GreenSuccessText,
                    Icons.Rounded.CheckCircle,
                    auditResult.description.takeIf { it.isNotBlank() }
                        ?: (if (currentTier == AudioQualityTier.HiRes || currentTier == AudioQualityTier.Master) "真高解析度" else "真无损"),
                )
            }
            auditResult?.verdict == AudioQualityVerdict.BANDWIDTH_LIMITED -> {
                Triple(
                    BlueInfoText,
                    Icons.Rounded.Info,
                    "频宽受限",
                )
            }
            isLossyTier || auditResult?.verdict == AudioQualityVerdict.LOSSY -> {
                Triple(
                    BlueInfoText,
                    Icons.Rounded.Info,
                    "标准有损",
                )
            }
            else -> {
                Triple(
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Icons.Rounded.Info,
                    "未检测",
                )
            }
        }

    val cardBg = accentColor.copy(alpha = 0.12f)
    val cardBorder = accentColor.copy(alpha = 0.35f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isAuditing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                                color = accentColor,
                            )
                        } else {
                            Icon(
                                imageVector = badgeIcon,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = badgeTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                    )
                }

                if (!isLossyTier) {
                    IconButton(
                        onClick = onRefreshAudit,
                        modifier = Modifier.size(28.dp),
                        enabled = !isAuditing,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "重新检测",
                            tint = accentColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val descText =
                when {
                    isAuditing -> "正在计算音频切片频谱分布与高频滚降特征..."
                    auditResult != null -> auditResult.details.ifBlank { auditResult.description }
                    isLossyTier -> "当前为标准有损编码（MP3 / AAC），高频截断属于正常声学压缩。"
                    else -> "点击右侧刷新按钮以对当前音频流执行频谱检测。"
                }

            Text(
                text = descText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp,
            )

            if (auditResult?.cutoffFrequencyHz != null && auditResult.cutoffFrequencyHz > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "有效频宽: ${(auditResult.cutoffFrequencyHz / 1000.0).format(1)} kHz",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun TechSpecSection(items: List<Pair<String, String>>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "技术规格",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            items.forEachIndexed { index, (label, value) ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = value.ifBlank { "未知" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (index < items.size - 1) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataSection(
    song: Song,
    localFilePath: String?,
    context: Context,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "标识符与路径",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            MetadataItem(
                label = "歌曲 MID",
                value = song.songMid,
                onClickCopy = { copyToClipboard(context, "歌曲 MID", song.songMid) },
            )

            if (song.albumMid.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                MetadataItem(
                    label = "专辑 MID",
                    value = song.albumMid,
                    onClickCopy = { copyToClipboard(context, "专辑 MID", song.albumMid) },
                )
            }

            if (song.effectiveMediaMid.isNotBlank() && song.effectiveMediaMid != song.songMid) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                MetadataItem(
                    label = "媒体 MID",
                    value = song.effectiveMediaMid,
                    onClickCopy = { copyToClipboard(context, "媒体 MID", song.effectiveMediaMid) },
                )
            }

            if (!localFilePath.isNullOrBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                MetadataItem(
                    label = "本地路径",
                    value = localFilePath,
                    onClickCopy = { copyToClipboard(context, "本地路径", localFilePath) },
                )
            }
        }
    }
}

@Composable
private fun MetadataItem(
    label: String,
    value: String,
    onClickCopy: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClickCopy() }
                .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = "复制",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "已复制 $label", Toast.LENGTH_SHORT).show()
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.2f MB".format(mb)
        kb >= 1.0 -> "%.1f KB".format(kb)
        else -> "$bytes B"
    }
}

private fun Double.format(digits: Int) = "%.${digits}f".format(this)
