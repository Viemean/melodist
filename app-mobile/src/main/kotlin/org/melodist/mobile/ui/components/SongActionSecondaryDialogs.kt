package org.melodist.mobile.ui.components

import android.media.MediaScannerConnection
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import org.melodist.data.LocalMusicManager
import org.melodist.data.download.DownloadManager
import org.melodist.model.Artist
import org.melodist.model.Song
import java.io.File

@Composable
fun DeleteLocalFileDialog(
    visible: Boolean,
    song: Song,
    localFilePath: String?,
    onDismissRequest: () -> Unit,
    onDeleteConfirmed: () -> Unit,
) {
    if (!visible || localFilePath == null) return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Rounded.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                text = "删除本地文件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                text = "确定要从设备中删除《${song.name}》的本地文件吗？此操作将永久移除该文件，无法撤销。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    val file = File(localFilePath)
                    if (file.exists()) {
                        file.delete()
                        val lrcFile = File(localFilePath.substringBeforeLast(".") + ".lrc")
                        if (lrcFile.exists()) {
                            lrcFile.delete()
                        }
                    }
                    LocalMusicManager.removeSongByPath(localFilePath)
                    DownloadManager.deleteDownloadedByPath(localFilePath)
                    DownloadManager.deleteDownloadedBySongMid(song.songMid, deleteFile = false)
                    try {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(localFilePath),
                            null,
                            null,
                        )
                    } catch (_: Exception) {
                    }
                    Toast.makeText(context, "已删除本地文件", Toast.LENGTH_SHORT).show()
                    onDeleteConfirmed()
                },
            ) {
                Text("确认删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        },
    )
}

@Composable
fun StoragePermissionDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "需要存储权限",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                text = "保存歌曲至系统的标准 Music/Melodist 目录需要授予“所有文件访问权限”，请在打开的系统设置中开启权限开关。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onRequestPermission()
                },
            ) {
                Text("前往设置授权")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        },
    )
}

@Composable
fun SongActionSecondaryDialogs(
    song: Song,
    localFilePath: String?,
    showDeleteConfirmDialog: Boolean,
    onDismissDeleteConfirmDialog: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    showArtistSelectDialog: Boolean,
    candidateArtists: List<Artist>,
    onDismissArtistSelectDialog: () -> Unit,
    onArtistSelected: (Artist) -> Unit,
    showPermissionDialog: Boolean,
    onDismissPermissionDialog: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    showAddToPlaylistDialog: Boolean,
    onDismissAddToPlaylistDialog: () -> Unit,
    onAddToPlaylistSuccess: () -> Unit,
    showCommentsSheet: Boolean,
    onDismissCommentsSheet: () -> Unit,
    showSongInfoSheet: Boolean,
    onDismissSongInfoSheet: () -> Unit,
) {
    if (showCommentsSheet) {
        SongCommentsBottomSheet(
            song = song,
            onDismissRequest = onDismissCommentsSheet,
        )
    }

    if (showSongInfoSheet) {
        SongInfoBottomSheet(
            song = song,
            onDismissRequest = onDismissSongInfoSheet,
        )
    }

    DeleteLocalFileDialog(
        visible = showDeleteConfirmDialog,
        song = song,
        localFilePath = localFilePath,
        onDismissRequest = onDismissDeleteConfirmDialog,
        onDeleteConfirmed = onDeleteConfirmed,
    )

    if (showArtistSelectDialog) {
        ArtistSelectDialog(
            artists = candidateArtists,
            onDismissRequest = onDismissArtistSelectDialog,
            onArtistSelect = onArtistSelected,
        )
    }

    StoragePermissionDialog(
        visible = showPermissionDialog,
        onDismissRequest = onDismissPermissionDialog,
        onRequestPermission = onRequestStoragePermission,
    )

    if (showAddToPlaylistDialog) {
        AddToPlaylistBottomSheet(
            songs = listOf(song),
            onDismissRequest = onDismissAddToPlaylistDialog,
            onSuccess = onAddToPlaylistSuccess,
        )
    }
}
