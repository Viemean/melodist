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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.melodist.data.UserLibraryCacheManager
import org.melodist.mobile.ui.discover.HeroRecommendCard
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

private const val SONG_ROTATION_INTERVAL_MS = 30_000L

@Composable
fun FavoriteMusicCard(
    totalCount: Int,
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val favoriteSongs by UserLibraryCacheManager.favoriteSongsFlow.collectAsState()
    val top10Songs = remember(favoriteSongs) { favoriteSongs.take(10) }

    var currentDisplayIndex by remember { mutableIntStateOf(0) }

    val isAppForeground by org.melodist.data.AppLifecycleManager.isForeground
        .collectAsState()

    // 确保索引有效并在前 10 首曲目间按顺序平滑循环（仅在应用处于前台时轮播）
    LaunchedEffect(top10Songs, isAppForeground) {
        if (top10Songs.size > 1 && isAppForeground) {
            while (isActive) {
                delay(SONG_ROTATION_INTERVAL_MS)
                currentDisplayIndex = (currentDisplayIndex + 1) % top10Songs.size
            }
        } else if (top10Songs.isEmpty()) {
            currentDisplayIndex = 0
        }
    }

    val activeSong: Song? = top10Songs.getOrNull(currentDisplayIndex) ?: top10Songs.firstOrNull()
    val activeCoverUrl = activeSong?.coverUrl.orEmpty()

    val triggerPlay: () -> Unit = {
        if (favoriteSongs.isNotEmpty()) {
            val startIndex =
                if (activeSong != null) {
                    val index =
                        favoriteSongs.indexOfFirst {
                            (it.songId > 0 && it.songId == activeSong.songId) ||
                                (it.songMid.isNotBlank() && it.songMid == activeSong.songMid)
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
        title = activeSong?.name ?: "我喜欢的音乐",
        caption =
            if (activeSong != null) {
                "${activeSong.singer} · ${activeSong.album.ifBlank { "单曲" }}"
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
