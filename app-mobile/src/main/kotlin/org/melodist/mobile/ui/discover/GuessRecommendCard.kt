package org.melodist.mobile.ui.discover

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.melodist.data.AppLifecycleManager
import org.melodist.data.GuessRecommendManager
import org.melodist.model.RotatingCandidatePool
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

private const val ROTATION_INTERVAL_MS = 20_000L
private const val PREFETCH_THRESHOLD_INDEX = 7

@Composable
fun GuessRecommendCard(
    onOpenPlayer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val guessSongs by GuessRecommendManager.songsFlow.collectAsState()
    val isPlaying by PlaybackManager.isPlaying.collectAsState()
    val isRadioMode by PlaybackManager.isRadioMode.collectAsState()
    val currentSong by PlaybackManager.currentSong.collectAsState()
    val isForeground by AppLifecycleManager.isForeground.collectAsState()

    var shuffledList by remember { mutableStateOf<List<Song>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }

    // 初始化与列表更新时重新构建打乱的候选队列
    LaunchedEffect(guessSongs) {
        if (guessSongs.isNotEmpty()) {
            shuffledList = RotatingCandidatePool.createShuffledQueue(guessSongs, lastItem = shuffledList.lastOrNull())
            currentIndex = 0
        } else {
            shuffledList = emptyList()
            currentIndex = 0
        }
    }

    // 15 秒无序轮播定时器，并在剩余约 3 首时提前预抓取下一批 10 首
    LaunchedEffect(shuffledList, isRadioMode, isForeground) {
        if (isRadioMode || !isForeground || shuffledList.size <= 1) return@LaunchedEffect
        while (isActive) {
            delay(ROTATION_INTERVAL_MS)
            val nextIndex = currentIndex + 1
            // 达到预抓取阈值时，静默提前抓取下一轮 10 首
            if (nextIndex >= PREFETCH_THRESHOLD_INDEX) {
                GuessRecommendManager.prefetchNextBatch()
            }

            if (nextIndex < shuffledList.size) {
                currentIndex = nextIndex
            } else {
                // 本轮 10 首已全部轮播完毕，切换到下一批并重新洗牌
                val nextSongs = GuessRecommendManager.rotateToNextBatch()
                shuffledList = RotatingCandidatePool.createShuffledQueue(nextSongs, lastItem = shuffledList.lastOrNull())
                currentIndex = 0
            }
        }
    }

    val activeSong =
        if (isRadioMode && currentSong != null) {
            currentSong
        } else {
            shuffledList.getOrNull(currentIndex) ?: guessSongs.firstOrNull()
        }
    val isCurrentRadioPlaying = isRadioMode && isPlaying

    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    val triggerPlay: () -> Unit = {
        if (isRadioMode && currentSong != null) {
            PlaybackManager.togglePlayPause()
        } else {
            val listToPlay = if (shuffledList.isNotEmpty()) shuffledList else guessSongs
            if (listToPlay.isNotEmpty()) {
                val startIndex =
                    if (activeSong != null) {
                        listToPlay
                            .indexOfFirst {
                                (it.songId > 0 && it.songId == activeSong.songId) ||
                                    (it.songMid.isNotBlank() && it.songMid == activeSong.songMid)
                            }.coerceAtLeast(0)
                    } else {
                        0
                    }
                PlaybackManager.setPlaylist(songs = listToPlay, startIndex = startIndex, isRadio = true)
            } else {
                scope.launch {
                    GuessRecommendManager.refresh(forceRefresh = true)
                    val freshList = GuessRecommendManager.songsFlow.value
                    if (freshList.isNotEmpty()) {
                        PlaybackManager.setPlaylist(songs = freshList, startIndex = 0, isRadio = true)
                    }
                }
            }
        }
    }

    HeroRecommendCard(
        badgeText = "猜你喜欢",
        subtitleText =
            if (isRadioMode) {
                if (isPlaying) "正在播放" else "已暂停"
            } else {
                "个性电台 · 20 秒无序轮播"
            },
        title = activeSong?.name ?: "个性推荐曲目",
        caption =
            if (activeSong != null) {
                "${activeSong.singer} · ${activeSong.album.ifBlank { "单曲" }}"
            } else {
                "根据听歌习惯与历史偏好生成"
            },
        coverUrl = activeSong?.coverUrl.orEmpty(),
        badgeIcon = Icons.Rounded.Radio,
        accentColor = primaryColor,
        accentContainerColor = primaryContainer,
        onAccentContainerColor = MaterialTheme.colorScheme.onPrimaryContainer,
        playIcon = if (isCurrentRadioPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
        playContentDescription = if (isCurrentRadioPlaying) "暂停" else "播放",
        onPlayClick = triggerPlay,
        onCardClick = triggerPlay,
        modifier = modifier,
    )
}
