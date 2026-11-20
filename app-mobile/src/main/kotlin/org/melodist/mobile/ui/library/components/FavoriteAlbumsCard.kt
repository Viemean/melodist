package org.melodist.mobile.ui.library.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
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
import org.melodist.model.Album
import org.melodist.model.RotatingCandidatePool

private const val ALBUM_ROTATION_INTERVAL_MS = 20_000L
private const val ALBUM_PHASE_OFFSET_MS = 10_000L

@Composable
fun FavoriteAlbumsCard(
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val libraryData by UserLibraryCacheManager.libraryFlow.collectAsState()
    val favoriteAlbums = libraryData.favoriteAlbums
    val isForeground by AppLifecycleManager.isForeground.collectAsState()

    val rotationState = remember { RotatingCardCoordinator.getOrCreate<Album>("favorite_albums") }
    var activeAlbum by remember { mutableStateOf(rotationState.currentItem) }

    LaunchedEffect(favoriteAlbums) {
        if (rotationState.updateSource(favoriteAlbums, fixedCount = 5, randomCount = 25)) {
            activeAlbum = rotationState.currentItem
        }
    }

    // 15 秒无序轮播定时器（错峰 4 秒，前台时执行）
    LaunchedEffect(isForeground, favoriteAlbums) {
        if (!isForeground || favoriteAlbums.size <= 1) return@LaunchedEffect
        delay(ALBUM_PHASE_OFFSET_MS)
        while (isActive) {
            delay(ALBUM_ROTATION_INTERVAL_MS)
            rotationState.advance()
            activeAlbum = rotationState.currentItem
        }
    }

    val displayAlbum = activeAlbum ?: favoriteAlbums.firstOrNull()

    HeroRecommendCard(
        badgeText = "收藏专辑",
        subtitleText = if (favoriteAlbums.isNotEmpty()) "共 ${favoriteAlbums.size} 张" else "暂无收藏",
        title = displayAlbum?.name ?: "收藏的专辑",
        caption =
            if (displayAlbum != null) {
                val artistText = displayAlbum.artist.ifBlank { "华语群星" }
                if (displayAlbum.songCount > 0) {
                    "$artistText · ${displayAlbum.songCount} 首歌曲"
                } else {
                    artistText
                }
            } else {
                "暂无收藏的专辑，去发现页逛逛吧"
            },
        coverUrl = displayAlbum?.coverUrl.orEmpty(),
        badgeIcon = Icons.Rounded.Album,
        accentColor = MaterialTheme.colorScheme.tertiary,
        accentContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
        onAccentContainerColor = MaterialTheme.colorScheme.onTertiaryContainer,
        onPlayClick = null,
        onCardClick = onCardClick,
        modifier = modifier,
    )
}
