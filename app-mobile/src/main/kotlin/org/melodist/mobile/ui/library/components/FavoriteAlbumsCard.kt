package org.melodist.mobile.ui.library.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.melodist.data.UserLibraryCacheManager
import org.melodist.mobile.ui.discover.HeroRecommendCard
import org.melodist.model.Album

private const val ALBUM_ROTATION_INTERVAL_MS = 30_000L

@Composable
fun FavoriteAlbumsCard(
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val libraryData by UserLibraryCacheManager.libraryFlow.collectAsState()
    val favoriteAlbums = libraryData.favoriteAlbums

    var activeAlbum by remember { mutableStateOf<Album?>(null) }

    val isAppForeground by org.melodist.data.AppLifecycleManager.isForeground.collectAsState()

    // 随机显示已收藏的专辑信息，若有多张则定时平滑轮转（仅在应用处于前台时轮播）
    LaunchedEffect(favoriteAlbums, isAppForeground) {
        if (favoriteAlbums.isNotEmpty()) {
            if (activeAlbum == null || favoriteAlbums.none { it.mid == activeAlbum?.mid }) {
                activeAlbum = favoriteAlbums.randomOrNull()
            }
            if (favoriteAlbums.size > 1 && isAppForeground) {
                while (isActive) {
                    delay(ALBUM_ROTATION_INTERVAL_MS)
                    val candidates = favoriteAlbums.filter { it.mid != activeAlbum?.mid }.ifEmpty { favoriteAlbums }
                    activeAlbum = candidates.randomOrNull() ?: favoriteAlbums.first()
                }
            }
        } else {
            activeAlbum = null
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
