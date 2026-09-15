package org.melodist.mobile.ui.discover

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.melodist.data.GuessRecommendManager
import org.melodist.playback.PlaybackManager

@Composable
fun GuessRecommendCard(
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val guessSongs by GuessRecommendManager.songsFlow.collectAsState()
    val isPlaying by PlaybackManager.isPlaying.collectAsState()
    val isRadioMode by PlaybackManager.isRadioMode.collectAsState()
    val currentSong by PlaybackManager.currentSong.collectAsState()

    val activeSong = if (isRadioMode && currentSong != null) currentSong else guessSongs.firstOrNull()
    val isCurrentRadioPlaying = isRadioMode && isPlaying

    val cardBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    val triggerPlay: () -> Unit = {
        if (isRadioMode && isPlaying) {
            onOpenPlayer()
        } else if (isRadioMode && !isPlaying) {
            PlaybackManager.togglePlayPause()
            onOpenPlayer()
        } else {
            val listToPlay = guessSongs
            if (listToPlay.isNotEmpty()) {
                PlaybackManager.setPlaylist(songs = listToPlay, startIndex = 0, isRadio = true)
                onOpenPlayer()
            } else {
                scope.launch {
                    GuessRecommendManager.refresh(forceRefresh = true)
                    val freshList = GuessRecommendManager.songsFlow.value
                    if (freshList.isNotEmpty()) {
                        PlaybackManager.setPlaylist(songs = freshList, startIndex = 0, isRadio = true)
                        onOpenPlayer()
                    }
                }
            }
        }
    }

    ElevatedCard(
        onClick = triggerPlay,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = cardBg),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors =
                                listOf(
                                    primaryContainer.copy(alpha = 0.35f),
                                    Color.Transparent,
                                ),
                        ),
                    ).padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧封面
                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(
                        targetState = activeSong?.coverUrl.orEmpty(),
                        label = "GuessCardCoverCrossfade",
                    ) { coverUrl ->
                        if (coverUrl.isNotBlank()) {
                            AsyncImage(
                                model = coverUrl,
                                contentDescription = activeSong?.name,
                                modifier =
                                    Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = null,
                                tint = primaryColor,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }

                    // 右下角微型 Radio 图标
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = CircleShape,
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(20.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = null,
                                tint = primaryColor,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // 中间信息
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = primaryColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = "猜你喜欢",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text =
                                if (isRadioMode) {
                                    if (isPlaying) "正在播放" else "已暂停"
                                } else {
                                    "每 3 分钟更新"
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = activeSong?.name ?: "个性推荐曲目",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text =
                            if (activeSong != null) {
                                "${activeSong.singer} · ${activeSong.album.ifBlank { "单曲" }}"
                            } else {
                                "根据听歌习惯与历史偏好生成"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 右侧播放按钮
                FilledTonalIconButton(
                    onClick = triggerPlay,
                    modifier = Modifier.size(46.dp),
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                ) {
                    Icon(
                        imageVector = if (isCurrentRadioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isCurrentRadioPlaying) "暂停" else "播放",
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}
