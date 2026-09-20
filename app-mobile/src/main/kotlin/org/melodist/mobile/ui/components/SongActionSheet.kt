package org.melodist.mobile.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaScannerConnection
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Tv
import org.melodist.core.connect.client.MobileConnectionState
import org.melodist.mobile.connect.MobileConnectManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.AddSongResult
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.addSongToPlaylist
import org.melodist.api.probeSongQualities
import org.melodist.data.AppSettingsManager
import org.melodist.data.LocalMusicManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.data.download.DownloadManager
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.mobile.ui.navigation.ScreenDestination
import org.melodist.model.Artist
import org.melodist.model.QualityOption
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionSheet(
    song: Song,
    onDismissRequest: () -> Unit,
    showNextPlay: Boolean = true,
    showFavorite: Boolean = true,
    isFromPlayer: Boolean = false,
    onViewCover: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onDeleteLocalFile: ((Song) -> Unit)? = null,
    onNavigate: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val navController = LocalAppNavigation.current
    val favoriteMids by PlaybackManager.favoriteSongMids.collectAsState()
    val isFavorite = favoriteMids.contains(song.songMid)
    val connectState by MobileConnectManager.connectionState.collectAsState()
    val isTvOnline = connectState is MobileConnectionState.Paired

    var showArtistSelectDialog by remember { mutableStateOf(false) }
    var showDownloadQualityDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showSongInfoSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showCommentsSheet by remember { mutableStateOf(false) }

    val localFilePath =
        remember(song) {
            val directPath = song.localFilePath?.takeIf { it.isNotBlank() }
            if (directPath != null && File(directPath).exists()) {
                directPath
            } else {
                DownloadManager.completedTasks.value.find { it.song.songMid == song.songMid }?.filePath?.takeIf {
                    File(it).exists()
                }
            }
        }

    var probedQualityOptions by remember(song.songMid) { mutableStateOf<List<QualityOption>>(emptyList()) }
    var isProbing by remember(song.songMid) { mutableStateOf(false) }

    LaunchedEffect(showDownloadQualityDialog, song.songMid) {
        if (showDownloadQualityDialog && probedQualityOptions.isEmpty()) {
            val playingSong = PlaybackManager.currentSong.value
            val globalProbed = PlaybackManager.probedQualityOptions.value
            if (playingSong?.songMid == song.songMid && globalProbed.isNotEmpty()) {
                probedQualityOptions = globalProbed
                return@LaunchedEffect
            }

            isProbing = true
            try {
                val probed =
                    withContext(Dispatchers.IO) {
                        val api = MusicApiService()
                        api.probeSongQualities(song.songMid, song.effectiveMediaMid)
                    }
                if (probed.isNotEmpty()) {
                    probedQualityOptions = probed
                }
            } catch (_: Exception) {
            } finally {
                isProbing = false
            }
        }
    }

    val artists =
        remember(song) {
            if (song.singerList.isNotEmpty()) {
                song.singerList
            } else {
                song.singer.split("/", "、", "&", ",").map { it.trim() }.filter { it.isNotBlank() }.map { name ->
                    Artist(id = 0L, mid = "", name = name)
                }
            }
        }

    val currentDest = navController.currentDestination

    // 处于专辑界面时，所有曲目均属于该专辑，无条件隐藏“查看专辑”，彻底杜绝套娃
    val isCurrentAlbumPage = currentDest is ScreenDestination.AlbumDetail

    // 处于歌手界面时：仅当歌曲是单个歌手且为当前歌手时才隐藏“查看歌手”；多位歌手合唱时完整保留所有歌手
    val isSingleArtistCurrentPage =
        remember(song, currentDest, artists) {
            if (currentDest is ScreenDestination.ArtistDetail) {
                val totalArtistCount =
                    if (artists.isNotEmpty()) {
                        artists.size
                    } else {
                        song.singer
                            .split("/", "、", "&", ",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .size
                    }
                if (totalArtistCount <= 1) {
                    val singleArtist = artists.firstOrNull()
                    val isSameMid = singleArtist?.mid?.isNotBlank() == true && singleArtist.mid == currentDest.artistMid
                    val isSameName =
                        (singleArtist?.name?.isNotBlank() == true && singleArtist.name == currentDest.artistName) ||
                            (song.singer.isNotBlank() && song.singer == currentDest.artistName)
                    isSameMid || isSameName
                } else {
                    false
                }
            } else {
                false
            }
        }

    val isWebDavOrLocal =
        song.isWebDav || song.isLocal || (!song.localFilePath.isNullOrBlank() && song.albumMid.isBlank() && song.singerList.none { it.mid.isNotBlank() })

    val canShowArtistAction =
        remember(isSingleArtistCurrentPage, artists, song, isWebDavOrLocal) {
            if (isWebDavOrLocal || isSingleArtistCurrentPage) {
                false
            } else {
                artists.isNotEmpty() || song.singer.isNotBlank()
            }
        }

    val candidateArtists = artists

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtImage(
                    coverUrl = song.thumbnailCoverUrl,
                    contentDescription = song.name,
                    shape = RoundedCornerShape(10.dp),
                    elevation = 4.dp,
                    placeholderIconSize = 28.dp,
                    modifier = Modifier.size(56.dp),
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${song.singer.ifBlank { "未知歌手" }} · ${song.album.ifBlank { "未知专辑" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(6.dp))

            if (showNextPlay) {
                ActionSheetItem(
                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    title = "下一首播放",
                    onClick = {
                        PlaybackManager.insertNextPlay(song)
                        Toast.makeText(context, "已加入下一首播放", Toast.LENGTH_SHORT).show()
                        onDismissRequest()
                    },
                )
            }

            if (isTvOnline) {
                ActionSheetItem(
                    icon = Icons.Rounded.Tv,
                    title = "在 TV 上立即播放",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        MobileConnectManager.playOnTv(song)
                        Toast.makeText(context, "已发送至 TV 播放", Toast.LENGTH_SHORT).show()
                        onDismissRequest()
                    },
                )
                ActionSheetItem(
                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    title = "在 TV 上稍后播放",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        MobileConnectManager.enqueueNextOnTv(song)
                        Toast.makeText(context, "已插播至 TV 队列", Toast.LENGTH_SHORT).show()
                        onDismissRequest()
                    },
                )
            }

            if (onViewCover != null) {
                ActionSheetItem(
                    icon = Icons.Rounded.Image,
                    title = "查看大图",
                    onClick = {
                        onDismissRequest()
                        onViewCover()
                    },
                )
            }

            if (showFavorite) {
                ActionSheetItem(
                    icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    title = if (isFavorite) "取消收藏" else "收藏到我喜欢的音乐",
                    tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        PlaybackManager.toggleSongFavorite(song)
                        Toast.makeText(context, if (isFavorite) "已取消收藏" else "已添加到收藏", Toast.LENGTH_SHORT).show()
                        onDismissRequest()
                    },
                )
            }

            if (!song.isLocal && !song.isWebDav) {
                ActionSheetItem(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    title = "添加到歌单",
                    onClick = {
                        if (!UserSession.isLoggedIn) {
                            Toast.makeText(context, "请先登录账号", Toast.LENGTH_SHORT).show()
                            return@ActionSheetItem
                        }
                        showAddToPlaylistDialog = true
                    },
                )
            }

            ActionSheetItem(
                icon = Icons.Rounded.ContentCopy,
                title = "复制歌曲信息",
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val content = buildString {
                        append("${song.name} - ${song.singer}")
                        if (!song.isLocal && !song.isWebDav && song.songMid.isNotBlank()) {
                            append("\nhttps://y.qq.com/n/ryqq/songDetail/${song.songMid}")
                        }
                    }
                    val clip = ClipData.newPlainText("song", content)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    onDismissRequest()
                },
            )

            if (!song.isLocal && !song.isWebDav) {
                ActionSheetItem(
                    icon = Icons.Rounded.Download,
                    title = "下载歌曲",
                    subtitle = "选择音质并内嵌原图与双语歌词",
                    onClick = {
                        showDownloadQualityDialog = true
                    },
                )
            }

            if (canShowArtistAction) {
                val displaySubtitle =
                    if (currentDest is ScreenDestination.ArtistDetail) {
                        candidateArtists.joinToString(" / ") { it.name }
                    } else {
                        song.singer.ifBlank { null }
                    }

                ActionSheetItem(
                    icon = Icons.Rounded.Person,
                    title = "查看歌手",
                    subtitle = displaySubtitle,
                    onClick = {
                        if (candidateArtists.size > 1) {
                            showArtistSelectDialog = true
                        } else {
                            val targetArtist = candidateArtists.firstOrNull()
                            val targetMid = targetArtist?.mid.orEmpty()
                            val targetName = targetArtist?.name?.ifBlank { song.singer } ?: song.singer
                            if (targetMid.isNotBlank()) {
                                onDismissRequest()
                                onNavigate?.invoke()
                                navController.navigateToArtist(targetMid, targetName, clearStack = isFromPlayer)
                            } else {
                                Toast.makeText(context, "暂无歌手详情数据", Toast.LENGTH_SHORT).show()
                                onDismissRequest()
                            }
                        }
                    },
                )
            }

            if (!isWebDavOrLocal && !isCurrentAlbumPage && (song.albumMid.isNotBlank() || song.album.isNotBlank())) {
                ActionSheetItem(
                    icon = Icons.Rounded.Album,
                    title = "查看专辑",
                    subtitle = song.album.ifBlank { null },
                    onClick = {
                        if (song.albumMid.isNotBlank()) {
                            onDismissRequest()
                            onNavigate?.invoke()
                            navController.navigateToAlbum(song.albumMid, song.album, clearStack = isFromPlayer)
                        } else {
                            Toast.makeText(context, "暂无专辑详情数据", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    },
                )
            }

            if (localFilePath != null) {
                ActionSheetItem(
                    icon = Icons.Rounded.DeleteForever,
                    title = "删除本地文件",
                    subtitle = "永久删除设备中的音频文件",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        showDeleteConfirmDialog = true
                    },
                )
            }

            if (onRemoveFromQueue != null) {
                ActionSheetItem(
                    icon = Icons.Rounded.DeleteOutline,
                    title = "从当前队列移除",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        onRemoveFromQueue()
                        onDismissRequest()
                    },
                )
            }

            if (!song.isLocal && !song.isWebDav) {
                ActionSheetItem(
                    icon = Icons.AutoMirrored.Rounded.Comment,
                    title = "查看评论",
                    subtitle = "精彩热评与最新讨论",
                    onClick = {
                        showCommentsSheet = true
                    },
                )
            }

            ActionSheetItem(
                icon = Icons.Rounded.Info,
                title = "查看歌曲信息",
                subtitle = "格式、采样率、位深与音质真伪",
                onClick = {
                    showSongInfoSheet = true
                },
            )
        }
    }

    SongActionSecondaryDialogs(
        song = song,
        localFilePath = localFilePath,
        showDeleteConfirmDialog = showDeleteConfirmDialog,
        onDismissDeleteConfirmDialog = { showDeleteConfirmDialog = false },
        onDeleteConfirmed = {
            onDeleteLocalFile?.invoke(song)
            onDismissRequest()
        },
        showArtistSelectDialog = showArtistSelectDialog,
        candidateArtists = candidateArtists,
        onDismissArtistSelectDialog = { showArtistSelectDialog = false },
        onArtistSelected = { artist ->
            showArtistSelectDialog = false
            onDismissRequest()
            if (artist.mid.isNotBlank()) {
                onNavigate?.invoke()
                navController.navigateToArtist(artist.mid, artist.name, clearStack = isFromPlayer)
            } else {
                Toast.makeText(context, "暂无歌手详情数据", Toast.LENGTH_SHORT).show()
            }
        },
        showPermissionDialog = showPermissionDialog,
        onDismissPermissionDialog = {
            showPermissionDialog = false
            onDismissRequest()
        },
        onRequestStoragePermission = {
            DownloadManager.requestStoragePermission(context)
        },
        showAddToPlaylistDialog = showAddToPlaylistDialog,
        onDismissAddToPlaylistDialog = { showAddToPlaylistDialog = false },
        onAddToPlaylistSuccess = { onDismissRequest() },
        showCommentsSheet = showCommentsSheet,
        onDismissCommentsSheet = { showCommentsSheet = false },
        showSongInfoSheet = showSongInfoSheet,
        onDismissSongInfoSheet = { showSongInfoSheet = false },
    )

    if (showDownloadQualityDialog) {
        val currentPreferredTier =
            AppSettingsManager.settings
                .collectAsState()
                .value.preferredQualityTier
        AudioQualityBottomSheet(
            title = "选择下载音质",
            currentTier = currentPreferredTier,
            availableTiers = song.availableTiers.toSet(),
            probedQualityOptions = probedQualityOptions,
            songDurationSec = song.durationSeconds,
            isProbing = isProbing,
            onSelectTier = { selectedTier ->
                showDownloadQualityDialog = false
                if (!DownloadManager.hasStoragePermission(context)) {
                    showPermissionDialog = true
                } else {
                    DownloadManager.downloadSong(song, preferredTier = selectedTier)
                    onDismissRequest()
                }
            },
            onDismissRequest = { showDownloadQualityDialog = false },
        )
    }
}

@Composable
private fun ActionSheetItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
