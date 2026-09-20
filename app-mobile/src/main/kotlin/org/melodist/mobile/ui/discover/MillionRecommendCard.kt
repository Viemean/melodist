package org.melodist.mobile.ui.discover

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.melodist.api.UserSession
import org.melodist.data.MillionRecommendManager
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.model.Playlist
import org.melodist.playback.PlaybackManager

@Composable
fun MillionRecommendCard(
    onRequireLogin: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = LocalAppNavigation.current
    val millionResult by MillionRecommendManager.resultFlow.collectAsState()
    val songs = millionResult.songs
    val coverUrl = millionResult.coverUrl.ifBlank { songs.firstOrNull()?.coverUrl.orEmpty() }

    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val tertiaryContainer = MaterialTheme.colorScheme.tertiaryContainer

    val openPlaylistDetail: () -> Unit = {
        if (!UserSession.isLoggedIn) {
            onRequireLogin()
        } else {
            val disstid = if (millionResult.disstid > 0L) millionResult.disstid else 211111L
            val playlist =
                Playlist(
                    dirId = disstid,
                    name = millionResult.title.ifBlank { "百万收藏" },
                    songCount = if (millionResult.totalSongNum > 0) millionResult.totalSongNum else 50,
                    tid = disstid,
                    isFav = true,
                    picUrl = coverUrl,
                    description = millionResult.description.ifBlank { "每一首歌曲都超过百万收藏 · 每日更新" },
                )
            navController.navigateToPlaylist(playlist)
        }
    }

    val triggerPlayAll: () -> Unit = {
        if (!UserSession.isLoggedIn) {
            onRequireLogin()
        } else if (songs.isNotEmpty()) {
            PlaybackManager.setPlaylist(songs = songs, startIndex = 0, isRadio = false)
            onOpenPlayer()
        } else {
            openPlaylistDetail()
        }
    }

    val displaySong by MillionRecommendManager.displaySongFlow.collectAsState()
    val activeSong = displaySong ?: songs.firstOrNull()
    val activeCoverUrl = activeSong?.coverUrl?.takeIf { it.isNotBlank() } ?: coverUrl

    HeroRecommendCard(
        badgeText = "百万收藏",
        subtitleText = "每日更新",
        title = activeSong?.name ?: millionResult.title.ifBlank { "官方高赞好歌专栏" },
        caption =
            if (activeSong != null) {
                "${activeSong.singer} · ${activeSong.album.ifBlank { "单曲" }}"
            } else {
                millionResult.description.ifBlank { "每一首歌曲都超过百万收藏 · 每日更新" }
            },
        coverUrl = activeCoverUrl,
        badgeIcon = Icons.Default.Whatshot,
        accentColor = tertiaryColor,
        accentContainerColor = tertiaryContainer,
        onAccentContainerColor = MaterialTheme.colorScheme.onTertiaryContainer,
        playIcon = Icons.Default.PlayArrow,
        playContentDescription = "播放百万收藏",
        onCardClick = openPlaylistDetail,
        onPlayClick = triggerPlayAll,
        modifier = modifier,
    )
}
