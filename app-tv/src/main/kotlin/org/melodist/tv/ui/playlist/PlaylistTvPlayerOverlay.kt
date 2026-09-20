package org.melodist.tv.ui.playlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.melodist.model.AudioQualityTier
import org.melodist.model.LyricLine
import org.melodist.model.Song
import org.melodist.playback.PlaybackLoopMode
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.components.BottomPlayerBar
import org.melodist.tv.ui.components.CenterAlignedKaraokeLyricsView
import org.melodist.tv.ui.theme.MelodistColors

@Composable
fun PlaylistTvLyricsOverlay(
    visible: Boolean,
    lyrics: List<LyricLine>,
    isPlaybackLoading: Boolean,
    themeHighlightColor: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(tween(durationMillis = 300)) + slideInHorizontally(tween(durationMillis = 300)) { 30 },
        exit = fadeOut(tween(durationMillis = 200)) + slideOutHorizontally(tween(durationMillis = 200)) { 30 },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (lyrics.isNotEmpty()) {
                val lyricsCurrentPositionMs by PlaybackManager.currentPositionMs.collectAsState()
                CenterAlignedKaraokeLyricsView(
                    lyrics = lyrics,
                    currentPositionMs = lyricsCurrentPositionMs,
                    highlightColor = themeHighlightColor,
                )
            } else if (isPlaybackLoading) {
                Text(
                    text = "正在拉取直链与同步歌词...",
                    color = MelodistColors.TextSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Text(
                    text = "暂无同步歌词",
                    color = MelodistColors.TextMuted,
                    fontSize = 18.sp,
                )
            }
        }
    }
}

@Composable
fun PlaylistTvBottomBar(
    visible: Boolean,
    surfaceColor: Color,
    accentColor: Color,
    horizontalSafePadding: Dp,
    activePlayingSong: Song?,
    durationMs: Long,
    isPlaying: Boolean,
    favoriteSongMids: Set<String>,
    loopMode: PlaybackLoopMode,
    selectedTier: AudioQualityTier,
    queueCount: Int,
    onFavoriteClick: () -> Unit,
    onPrevClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onLoopClick: () -> Unit,
    onQualityClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onQueueClick: () -> Unit,
    onDownPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter =
            slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 300),
            ) + fadeIn(animationSpec = tween(durationMillis = 300)),
        exit =
            slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 300),
            ) + fadeOut(animationSpec = tween(durationMillis = 300)),
    ) {
        val canFavorite = PlaybackManager.isSongFavoriteSupported(activePlayingSong)
        val totalDurationMs =
            if (durationMs > 0L) {
                durationMs
            } else {
                activePlayingSong?.durationSeconds?.toLong()?.times(1000L) ?: 240000L
            }
        val canChangeQuality = !PlaybackManager.isLocalOrWebDavSong(activePlayingSong)

        BottomPlayerBar(
            modifier = Modifier.fillMaxWidth(),
            surfaceColor = surfaceColor,
            accentColor = accentColor,
            horizontalPadding = horizontalSafePadding,
            progressMsProvider = { PlaybackManager.currentPositionMs.value },
            durationMs = totalDurationMs,
            isPlaying = isPlaying,
            isFavorite = activePlayingSong?.songMid in favoriteSongMids,
            canFavorite = canFavorite,
            loopMode = loopMode.label,
            qualityLabel = AudioQualityTier.getBadge(selectedTier),
            canChangeQuality = canChangeQuality,
            onFavoriteClick = onFavoriteClick,
            onPrevClick = onPrevClick,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = onNextClick,
            onLoopClick = onLoopClick,
            onQualityClick = onQualityClick,
            onFullscreenClick = onFullscreenClick,
            onSeekBy = onSeekBy,
            queueCount = queueCount,
            onQueueClick = onQueueClick,
            onDownPress = onDownPress,
        )
    }
}

@Composable
fun PlaylistTvFullscreenRestoreOverlay(
    visible: Boolean,
    onWakeControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (visible) {
        val restoreRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            restoreRequester.requestFocus()
        }
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .focusRequester(restoreRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            onWakeControls()
                            true
                        } else {
                            true
                        }
                    }.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onWakeControls()
                    },
        )
    }
}
