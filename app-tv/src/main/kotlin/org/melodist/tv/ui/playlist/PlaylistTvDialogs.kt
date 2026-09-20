package org.melodist.tv.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import org.melodist.tv.ui.components.AudioQualityDialog
import org.melodist.tv.ui.components.PlayerQueueSidebar
import org.melodist.tv.ui.components.SongArtistAlbumDialog

@Composable
fun BoxScope.PlaylistTvDialogs(
    showQueueSidebar: Boolean,
    onDismissQueueSidebar: () -> Unit,
    playlist: List<Song>,
    currentSong: Song?,
    surfaceColor: Color,
    onSelectQueueSong: (Song) -> Unit,
    showQualityDialog: Boolean,
    selectedTier: AudioQualityTier,
    onSelectTier: (AudioQualityTier) -> Unit,
    onDismissQualityDialog: () -> Unit,
    showArtistAlbumDialog: Boolean,
    onDismissArtistAlbumDialog: () -> Unit,
    actionSong: Song?,
    onDismissActionSongDialog: () -> Unit,
    onNavigateToArtist: (mid: String, name: String) -> Unit,
    onNavigateToAlbum: (mid: String, name: String) -> Unit,
) {
    // 侧边栏外部空白点击关闭遮罩
    if (showQueueSidebar) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismissQueueSidebar() },
        )
    }

    // 播放队列侧边栏
    PlayerQueueSidebar(
        playlist = playlist,
        currentSong = currentSong,
        surfaceColor = surfaceColor,
        isOpen = showQueueSidebar,
        onSelectSong = onSelectQueueSong,
        onDismiss = onDismissQueueSidebar,
        modifier = Modifier.align(Alignment.CenterEnd),
    )

    // 浮动音质选择弹窗
    if (showQualityDialog) {
        AudioQualityDialog(
            selectedTier = selectedTier,
            onSelectTier = onSelectTier,
            onDismiss = onDismissQualityDialog,
        )
    }

    // 浮动歌手/专辑选择弹窗（播放模式方向键下呼出）
    if (showArtistAlbumDialog && currentSong != null && currentSong.canShowArtistAlbumDialog) {
        SongArtistAlbumDialog(
            song = currentSong,
            onDismissRequest = onDismissArtistAlbumDialog,
            onSelectArtist = { mid, name ->
                onDismissArtistAlbumDialog()
                onNavigateToArtist(mid, name)
            },
            onSelectAlbum = { mid, name ->
                onDismissArtistAlbumDialog()
                onNavigateToAlbum(mid, name)
            },
        )
    }

    // 歌曲关联资产弹窗（单曲列表长按呼出）
    actionSong?.let { song ->
        SongArtistAlbumDialog(
            song = song,
            onDismissRequest = onDismissActionSongDialog,
            onSelectArtist = { mid, name ->
                onDismissActionSongDialog()
                onNavigateToArtist(mid, name)
            },
            onSelectAlbum = { mid, name ->
                onDismissActionSongDialog()
                onNavigateToAlbum(mid, name)
            },
        )
    }
}
