package org.melodist.mobile.ui.components.songlist

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.deleteSongsFromPlaylist
import org.melodist.data.LocalMusicManager
import org.melodist.data.RecentPlaybackManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.data.WebDavManager
import org.melodist.data.download.DownloadManager
import org.melodist.model.Song
import org.melodist.mobile.ui.components.AddToPlaylistBottomSheet
import org.melodist.mobile.ui.components.SongListDeleteType
import org.melodist.playback.PlaybackManager

/**
 * 歌曲列表批量操作弹窗群组组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListBatchDialogs(
    showBatchDownloadDialog: Boolean,
    onDismissBatchDownload: () -> Unit,
    showBatchDeleteDialog: Boolean,
    onDismissBatchDelete: () -> Unit,
    showAddToMenuDialog: Boolean,
    onDismissAddToMenu: () -> Unit,
    showAddToPlaylistDialog: Boolean,
    onDismissAddToPlaylist: () -> Unit,
    onOpenAddToPlaylist: () -> Unit,
    selectedSongs: List<Song>,
    deleteType: SongListDeleteType,
    playlistDirId: Long,
    apiService: MusicApiService,
    onDeleteSelected: (suspend (List<Song>) -> Unit)?,
    onExitMultiSelect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isOperating by remember { mutableStateOf(false) }

    // 1. 批量下载确认弹窗
    if (showBatchDownloadDialog) {
        val validDownloadSongs = selectedSongs.filter { it.songMid.isNotBlank() && !it.isLocal && !it.isWebDav }
        AlertDialog(
            onDismissRequest = onDismissBatchDownload,
            title = { Text("批量下载确认") },
            text = {
                Text("确定将选中的 ${validDownloadSongs.size} 首在线歌曲加入下载队列吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissBatchDownload()
                        if (!DownloadManager.hasStoragePermission(context)) {
                            DownloadManager.requestStoragePermission(context)
                        }
                        var count = 0
                        for (song in validDownloadSongs) {
                            DownloadManager.downloadSong(song)
                            count++
                        }
                        Toast.makeText(context, "已将 $count 首歌曲加入下载队列", Toast.LENGTH_SHORT).show()
                        onExitMultiSelect()
                    },
                ) {
                    Text("确定下载")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBatchDownload) {
                    Text("取消")
                }
            },
        )
    }

    // 2. 批量删除确认弹窗
    if (showBatchDeleteDialog) {
        val count = selectedSongs.size
        val deleteMessage =
            when (deleteType) {
                SongListDeleteType.PlaylistTrack -> "确定从歌单中移除选中的 $count 首歌曲吗？"
                SongListDeleteType.LocalFile -> "确定删除选中的 $count 个本地歌曲文件吗？此操作不可撤销。"
                SongListDeleteType.WebDavFile -> "确定从 WebDAV 曲库中移除选中的 $count 首歌曲吗？"
                SongListDeleteType.RecentHistory -> "确定删除选中的 $count 条播放历史记录吗？"
                SongListDeleteType.PlayerQueue -> "确定从当前播放队列中移除选中的 $count 首歌曲吗？"
                SongListDeleteType.None -> ""
            }

        AlertDialog(
            onDismissRequest = { if (!isOperating) onDismissBatchDelete() },
            title = { Text("删除确认") },
            text = { Text(deleteMessage) },
            confirmButton = {
                TextButton(
                    enabled = !isOperating,
                    onClick = {
                        isOperating = true
                        scope.launch {
                            try {
                                when (deleteType) {
                                    SongListDeleteType.PlaylistTrack -> {
                                        if (onDeleteSelected != null) {
                                            onDeleteSelected(selectedSongs)
                                        } else if (playlistDirId > 0L) {
                                            val success = apiService.deleteSongsFromPlaylist(playlistDirId, selectedSongs)
                                            if (success) {
                                                UserLibraryCacheManager.onSongsRemovedFromPlaylist(playlistDirId, selectedSongs)
                                                if (playlistDirId == 201L) {
                                                    val mids = selectedSongs.map { it.songMid }.toSet()
                                                    val currentFavs = PlaybackManager.favoriteSongMids.value.filter { !mids.contains(it) }
                                                    PlaybackManager.setFavoriteSongMids(currentFavs.toSet())
                                                }
                                                Toast.makeText(context, "已从歌单移除 $count 首歌曲", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "移除失败", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    SongListDeleteType.LocalFile -> {
                                        LocalMusicManager.deleteSongs(selectedSongs, context)
                                        onDeleteSelected?.invoke(selectedSongs)
                                        Toast.makeText(context, "已删除本地文件", Toast.LENGTH_SHORT).show()
                                    }
                                    SongListDeleteType.WebDavFile -> {
                                        WebDavManager.removeSongsFromCache(selectedSongs)
                                        onDeleteSelected?.invoke(selectedSongs)
                                        Toast.makeText(context, "已从曲库移除", Toast.LENGTH_SHORT).show()
                                    }
                                    SongListDeleteType.RecentHistory -> {
                                        RecentPlaybackManager.removeSongs(selectedSongs)
                                        onDeleteSelected?.invoke(selectedSongs)
                                        Toast.makeText(context, "已删除历史记录", Toast.LENGTH_SHORT).show()
                                    }
                                    SongListDeleteType.PlayerQueue -> {
                                        PlaybackManager.removeFromPlaylist(selectedSongs)
                                        onDeleteSelected?.invoke(selectedSongs)
                                        Toast.makeText(context, "已从播放队列移除", Toast.LENGTH_SHORT).show()
                                    }
                                    SongListDeleteType.None -> {}
                                }
                            } finally {
                                isOperating = false
                                onDismissBatchDelete()
                                onExitMultiSelect()
                            }
                        }
                    },
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isOperating,
                    onClick = onDismissBatchDelete,
                ) {
                    Text("取消")
                }
            },
        )
    }

    // 3. 批量添加操作二级菜单
    if (showAddToMenuDialog) {
        val onlineSongs = selectedSongs.filter { it.songMid.isNotBlank() && !it.isLocal && !it.isWebDav }
        ModalBottomSheet(
            onDismissRequest = onDismissAddToMenu,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
            ) {
                Text(
                    text = "批量添加 (${selectedSongs.size}首)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )

                // 3.1 添加到队列（通用）
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismissAddToMenu()
                                PlaybackManager.appendPlaylist(selectedSongs)
                                Toast.makeText(context, "已将 ${selectedSongs.size} 首歌曲添加到播放队列", Toast.LENGTH_SHORT).show()
                                onExitMultiSelect()
                            }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "添加到播放队列",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                // 3.2 添加到歌单（仅在线歌曲可用）
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismissAddToMenu()
                                if (onlineSongs.isEmpty()) {
                                    Toast.makeText(context, "所选歌曲中没有可添加的在线音乐", Toast.LENGTH_SHORT).show()
                                } else {
                                    onOpenAddToPlaylist()
                                }
                            }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        contentDescription = null,
                        tint =
                            if (onlineSongs.isNotEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            },
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "添加到歌单",
                            style = MaterialTheme.typography.bodyLarge,
                            color =
                                if (onlineSongs.isNotEmpty()) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                        )
                        if (onlineSongs.size < selectedSongs.size) {
                            Text(
                                text = "包含 ${onlineSongs.size} 首在线歌曲",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // 4. 添加到自建歌单选择器
    if (showAddToPlaylistDialog) {
        val onlineSongs = selectedSongs.filter { it.songMid.isNotBlank() && !it.isLocal && !it.isWebDav }
        AddToPlaylistBottomSheet(
            songs = onlineSongs,
            onDismissRequest = onDismissAddToPlaylist,
            onSuccess = onExitMultiSelect,
        )
    }
}
