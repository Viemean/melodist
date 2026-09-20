package org.melodist.mobile.ui.discover

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.melodist.data.GuessRecommendManager
import org.melodist.playback.PlaybackManager

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

    val activeSong = if (isRadioMode && currentSong != null) currentSong else guessSongs.firstOrNull()
    val isCurrentRadioPlaying = isRadioMode && isPlaying

    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    val triggerPlay: () -> Unit = {
        if (isRadioMode) {
            PlaybackManager.togglePlayPause()
        } else {
            val listToPlay = guessSongs
            if (listToPlay.isNotEmpty()) {
                PlaybackManager.setPlaylist(songs = listToPlay, startIndex = 0, isRadio = true)
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
                "每 3 分钟更新"
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
        onCardClick = null,
        modifier = modifier,
    )
}
