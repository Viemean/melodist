package org.melodist.mobile.ui.discover

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Whatshot
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
import org.melodist.api.UserSession
import org.melodist.data.AppLifecycleManager
import org.melodist.data.MillionRecommendManager
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.model.Playlist
import org.melodist.model.RotatingCandidatePool
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

private const val ROTATION_INTERVAL_MS = 15_000L
private const val PHASE_OFFSET_MS = 4_000L // 与猜你喜欢错峰 4 秒

@Composable
fun MillionRecommendCard(
    onRequireLogin: () -> Unit,
    onOpenPlayer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val navController = LocalAppNavigation.current
    val millionResult by MillionRecommendManager.resultFlow.collectAsState()
    val songs = millionResult.songs
    val coverUrl = millionResult.coverUrl.ifBlank { songs.firstOrNull()?.coverUrl.orEmpty() }
    val isForeground by AppLifecycleManager.isForeground.collectAsState()

    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val tertiaryContainer = MaterialTheme.colorScheme.tertiaryContainer

    // 提取前 50 首本地缓存作为候选池并进行洗牌
    val pool50 = remember(songs) { songs.take(50) }
    var shuffledList by remember { mutableStateOf<List<Song>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(pool50) {
        if (pool50.isNotEmpty()) {
            shuffledList = RotatingCandidatePool.createShuffledQueue(pool50, lastItem = shuffledList.lastOrNull())
            currentIndex = 0
        } else {
            shuffledList = emptyList()
            currentIndex = 0
        }
    }

    // 15 秒无序轮播，带有 4 秒初始错峰相位，避免与同屏的猜你喜欢同时跳变
    LaunchedEffect(shuffledList, isForeground) {
        if (!isForeground || shuffledList.size <= 1) return@LaunchedEffect
        delay(PHASE_OFFSET_MS)
        while (isActive) {
            delay(ROTATION_INTERVAL_MS)
            val nextIndex = currentIndex + 1
            if (nextIndex < shuffledList.size) {
                currentIndex = nextIndex
            } else {
                shuffledList = RotatingCandidatePool.createShuffledQueue(pool50, lastItem = shuffledList.lastOrNull())
                currentIndex = 0
            }
        }
    }

    val activeSong = shuffledList.getOrNull(currentIndex) ?: songs.firstOrNull()
    val activeCoverUrl = activeSong?.coverUrl?.takeIf { it.isNotBlank() } ?: coverUrl

    val openPlaylistDetail: () -> Unit = {
        if (!UserSession.isLoggedIn) {
            onRequireLogin()
        } else {
            val disstid = if (millionResult.disstid > 0L) millionResult.disstid else 211111L
            val playlist =
                Playlist(
                    dirId = 211111L,
                    name = millionResult.title.ifBlank { "百万收藏" },
                    songCount = if (millionResult.totalSongNum > 0) millionResult.totalSongNum else (if (songs.isNotEmpty()) songs.size else 50),
                    tid = disstid,
                    isFav = true,
                    picUrl = coverUrl,
                    description = millionResult.description.ifBlank { "每一首歌曲都超过百万收藏 · 每日更新" },
                )
            navController.navigateToPlaylist(playlist)
        }
    }

    val triggerPlay: () -> Unit = {
        if (!UserSession.isLoggedIn) {
            onRequireLogin()
        } else if (songs.isNotEmpty()) {
            val startIndex =
                if (activeSong != null) {
                    songs
                        .indexOfFirst {
                            (it.songId > 0 && it.songId == activeSong.songId) ||
                                (it.songMid.isNotBlank() && it.songMid == activeSong.songMid)
                        }.coerceAtLeast(0)
                } else {
                    0
                }
            PlaybackManager.setPlaylist(songs = songs, startIndex = startIndex, isRadio = false)
        } else {
            openPlaylistDetail()
        }
    }

    HeroRecommendCard(
        badgeText = "百万收藏",
        subtitleText = "高赞专栏 · 15 秒无序轮播",
        title = activeSong?.name ?: millionResult.title.ifBlank { "官方高赞好歌专栏" },
        caption =
            if (activeSong != null) {
                "${activeSong.singer} · ${activeSong.album.ifBlank { "单曲" }}"
            } else {
                millionResult.description.ifBlank { "每一首歌曲都超过百万收藏 · 每日更新" }
            },
        coverUrl = activeCoverUrl,
        badgeIcon = Icons.Rounded.Whatshot,
        accentColor = tertiaryColor,
        accentContainerColor = tertiaryContainer,
        onAccentContainerColor = MaterialTheme.colorScheme.onTertiaryContainer,
        playIcon = Icons.Rounded.PlayArrow,
        playContentDescription = "播放百万收藏",
        onCardClick = openPlaylistDetail,
        onPlayClick = triggerPlay,
        modifier = modifier,
    )
}
