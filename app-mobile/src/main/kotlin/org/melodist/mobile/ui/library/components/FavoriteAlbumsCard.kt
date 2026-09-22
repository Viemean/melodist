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
import org.melodist.mobile.ui.discover.HeroRecommendCard
import org.melodist.model.Album
import org.melodist.model.RotatingCandidatePool

private const val ALBUM_ROTATION_INTERVAL_MS = 15_000L
private const val ALBUM_PHASE_OFFSET_MS = 4_000L

@Composable
fun FavoriteAlbumsCard(
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val libraryData by UserLibraryCacheManager.libraryFlow.collectAsState()
    val favoriteAlbums = libraryData.favoriteAlbums
    val isForeground by AppLifecycleManager.isForeground.collectAsState()

    // 5 最新 + 25 随机构建候选池
    val candidatePool = remember(favoriteAlbums) {
        RotatingCandidatePool.buildCandidatePool(favoriteAlbums, fixedCount = 5, randomCount = 25)
    }

    var shuffledList by remember { mutableStateOf<List<Album>>(emptyList()) }
    var currentDisplayIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(candidatePool) {
        if (candidatePool.isNotEmpty()) {
            shuffledList = RotatingCandidatePool.createShuffledQueue(candidatePool, lastItem = shuffledList.lastOrNull())
            currentDisplayIndex = 0
        } else {
            shuffledList = emptyList()
            currentDisplayIndex = 0
        }
    }

    // 15 秒无序轮播定时器（错峰 4 秒，前台时执行）
    LaunchedEffect(shuffledList, isForeground) {
        if (!isForeground || shuffledList.size <= 1) return@LaunchedEffect
        delay(ALBUM_PHASE_OFFSET_MS)
        while (isActive) {
            delay(ALBUM_ROTATION_INTERVAL_MS)
            val nextIndex = currentDisplayIndex + 1
            if (nextIndex < shuffledList.size) {
                currentDisplayIndex = nextIndex
            } else {
                shuffledList = RotatingCandidatePool.createShuffledQueue(candidatePool, lastItem = shuffledList.lastOrNull())
                currentDisplayIndex = 0
            }
        }
    }

    val displayAlbum = shuffledList.getOrNull(currentDisplayIndex) ?: favoriteAlbums.firstOrNull()

    HeroRecommendCard(
        badgeText = "收藏专辑",
        subtitleText = if (favoriteAlbums.isNotEmpty()) "共 ${favoriteAlbums.size} 张 · 15 秒无序轮播" else "暂无收藏",
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
