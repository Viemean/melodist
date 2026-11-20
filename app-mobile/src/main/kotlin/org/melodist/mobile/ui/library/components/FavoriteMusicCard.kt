package org.melodist.mobile.ui.library.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.melodist.data.AppLifecycleManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.mobile.ui.components.RotatingCardCoordinator
import org.melodist.mobile.ui.discover.HeroRecommendCard
import org.melodist.model.RotatingCandidatePool
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

private const val SONG_ROTATION_INTERVAL_MS = 20_000L

@Composable
fun FavoriteMusicCard(
    totalCount: Int,
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val favoriteSongs by UserLibraryCacheManager.favoriteSongsFlow.collectAsState()
    val isForeground by AppLifecycleManager.isForeground.collectAsState()

    val rotationState = remember { RotatingCardCoordinator.getOrCreate<Song>("favorite_music") }
    var activeSong by remember { mutableStateOf(rotationState.currentItem) }

    LaunchedEffect(favoriteSongs) {
        if (rotationState.updateSource(favoriteSongs, fixedCount = 5, randomCount = 25)) {
            activeSong = rotationState.currentItem
        }
    }

    LaunchedEffect(isForeground, favoriteSongs) {
        if (!isForeground || favoriteSongs.size <= 1) return@LaunchedEffect
        while (isActive) {
            delay(SONG_ROTATION_INTERVAL_MS)
            rotationState.advance()
            activeSong = rotationState.currentItem
        }
    }

    val currentSong: Song? = activeSong ?: favoriteSongs.firstOrNull()
    val activeCoverUrl = currentSong?.coverUrl.orEmpty()

    val triggerPlay: () -> Unit = {
        if (favoriteSongs.isNotEmpty()) {
            val startIndex =
                if (currentSong != null) {
                    val index =
                        favoriteSongs.indexOfFirst {
                            (it.songId > 0 && it.songId == currentSong.songId) ||
                                (it.songMid.isNotBlank() && it.songMid == currentSong.songMid)
                        }
                    if (index >= 0) index else 0
                } else {
                    0
                }
            PlaybackManager.setPlaylist(
                songs = favoriteSongs,
                startIndex = startIndex,
                isRadio = false,
            )
        } else {
            onCardClick()
        }
    }

    val effectiveTotalCount = if (totalCount > 0) totalCount else favoriteSongs.size

    HeroRecommendCard(
        badgeText = "我的喜欢",
        subtitleText = "共 $effectiveTotalCount 首",
        title = currentSong?.name ?: "我喜欢的音乐",
        caption =
            if (currentSong != null) {
                "${currentSong.singer} · ${currentSong.album.ifBlank { "单曲" }}"
            } else {
                "点亮红心，收藏你挚爱的旋律"
            },
        coverUrl = activeCoverUrl,
        badgeIcon = Icons.Rounded.Favorite,
        accentColor = MaterialTheme.colorScheme.error,
        accentContainerColor = MaterialTheme.colorScheme.errorContainer,
        onAccentContainerColor = MaterialTheme.colorScheme.onErrorContainer,
        playIcon = Icons.Rounded.PlayArrow,
        playContentDescription = "播放我的喜欢",
        onPlayClick = triggerPlay,
        onCardClick = onCardClick,
        modifier = modifier,
    )
}
