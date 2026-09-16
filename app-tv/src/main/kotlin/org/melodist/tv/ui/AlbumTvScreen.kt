package org.melodist.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.data.ArtistAlbumCacheManager
import org.melodist.model.AlbumDetail
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.components.MediaDetailTvScaffold
import org.melodist.tv.ui.components.SongArtistAlbumDialog

/**
 * 电视端独立专辑详情界面
 * 基于 MediaDetailTvScaffold 通用骨架，支持遥控器左右双向互通导航
 */
@Composable
fun AlbumTvScreen(
    albumMid: String,
    albumName: String = "",
    surfaceColor: Color,
    onNavigateToPlayer: () -> Unit = {},
    onNavigateToArtist: (artistMid: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToAlbum: (albumMid: String, albumName: String) -> Unit = { _, _ -> },
    isReturningFromPlayer: Boolean = false,
    onBack: () -> Unit = {},
) {
    var albumDetail by remember { mutableStateOf<AlbumDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedSongForDialog by remember { mutableStateOf<Song?>(null) }

    val currentPlayingSong by PlaybackManager.currentSong.collectAsState()
    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }

    LaunchedEffect(albumMid) {
        if (albumMid.isNotBlank()) {
            isLoading = true
            albumDetail = ArtistAlbumCacheManager.getAlbumDetail(albumMid)
            isLoading = false
        }
    }

    val coverUrl =
        remember(albumMid) {
            if (albumMid.isNotBlank()) MusicApiService.getAlbumCoverUrl(albumMid) else ""
        }

    val title = albumDetail?.name ?: albumName.ifBlank { "专辑详情" }
    val subtitle = albumDetail?.artist ?: ""
    val songs = albumDetail?.songs.orEmpty()
    val description = albumDetail?.description ?: if (songs.isNotEmpty()) "共收录 ${songs.size} 首单曲" else ""

    MediaDetailTvScaffold(
        surfaceColor = surfaceColor,
        headerImageUrl = coverUrl,
        albumMid = albumMid,
        isHeaderImageCircle = false,
        title = title,
        subtitle = subtitle,
        metaInfo = if (albumDetail?.publishDate?.isNotBlank() == true) "发行时间: ${albumDetail?.publishDate}" else "",
        description = description,
        songs = songs,
        currentPlayingMid = currentPlayingSong?.songMid,
        isLoading = isLoading,
        emptyMessage = "专辑暂无曲目数据",
        isReturningFromPlayer = isReturningFromPlayer,
        onBack = onBack,
        onPlayAll = {
            if (songs.isNotEmpty()) {
                PlaybackManager.setPlaylist(songs, startIndex = 0)
                onNavigateToPlayer()
            }
        },
        onSongClick = { song ->
            val index = songs.indexOfFirst { it.songMid == song.songMid }
            PlaybackManager.setPlaylist(songs, startIndex = if (index >= 0) index else 0)
            onNavigateToPlayer()
        },
        onSongLongClick = { song ->
            if (song.canShowArtistAlbumDialog) {
                selectedSongForDialog = song
            }
        },
    )

    // 长按曲目关联资产弹窗
    selectedSongForDialog?.let { song ->
        SongArtistAlbumDialog(
            song = song,
            onDismissRequest = { selectedSongForDialog = null },
            onSelectArtist = { mid, name ->
                selectedSongForDialog = null
                onNavigateToArtist(mid, name)
            },
            onSelectAlbum = { mid, name ->
                selectedSongForDialog = null
                if (mid != albumMid) {
                    onNavigateToAlbum(mid, name)
                }
            },
        )
    }
}
