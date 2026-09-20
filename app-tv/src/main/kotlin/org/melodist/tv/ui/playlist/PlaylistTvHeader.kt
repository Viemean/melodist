package org.melodist.tv.ui.playlist

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.melodist.model.Album
import org.melodist.model.Playlist
import org.melodist.model.Song
import org.melodist.tv.ui.components.MelodistElevatedCover
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes

@Composable
fun PlaylistTvLeftPanel(
    coverSize: Dp,
    isPlayerMode: Boolean,
    activePlayingSong: Song?,
    displayCoverUrl: String,
    displayAlbumMid: String,
    resolvedTitle: String,
    displaySubtitle: String,
    categoryId: String,
    playbackErrorMessage: String?,
    hasSongs: Boolean,
    playAllRequester: FocusRequester,
    firstSongRequester: FocusRequester,
    onPlayAllClick: () -> Unit,
    onPlayAllFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val effectiveCoverUrl =
            if (isPlayerMode && activePlayingSong != null && activePlayingSong.coverUrl.isNotBlank()) {
                activePlayingSong.coverUrl
            } else {
                displayCoverUrl
            }
        val effectiveAlbumMid =
            if (isPlayerMode && activePlayingSong != null && activePlayingSong.albumMid.isNotBlank()) {
                activePlayingSong.albumMid
            } else {
                displayAlbumMid
            }

        // 大屏大封面展示
        if (effectiveCoverUrl.isNotEmpty() || effectiveAlbumMid.isNotEmpty()) {
            MelodistElevatedCover(
                coverUrl = effectiveCoverUrl,
                albumMid = effectiveAlbumMid,
                visualMid = if (isPlayerMode) activePlayingSong?.visualMid.orEmpty() else "",
                songMid = if (isPlayerMode) activePlayingSong?.songMid.orEmpty() else "",
                contentDescription = if (isPlayerMode) activePlayingSong?.name.orEmpty() else resolvedTitle,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.size(coverSize),
            )
        } else if (!isPlayerMode && categoryId == "favorites") {
            Box(
                modifier =
                    Modifier
                        .size(coverSize)
                        .shadow(
                            elevation = 10.dp,
                            shape = RoundedCornerShape(6.dp),
                            ambientColor = Color.Black.copy(alpha = 0.25f),
                            spotColor = Color.Black.copy(alpha = 0.45f),
                        ).clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF38151D))
                        .border(BorderStroke(0.75.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "我喜欢",
                        tint = MelodistColors.FavoriteRed,
                        modifier = Modifier.size(96.dp),
                    )
                    Text(
                        text = "MELODIST FAVORITES",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
            }
        } else {
            Box(
                modifier =
                    Modifier
                        .size(coverSize)
                        .shadow(
                            elevation = 10.dp,
                            shape = RoundedCornerShape(6.dp),
                            ambientColor = Color.Black.copy(alpha = 0.25f),
                            spotColor = Color.Black.copy(alpha = 0.45f),
                        ).clip(RoundedCornerShape(6.dp))
                        .background(MelodistColors.ContainerDarkSecondary)
                        .border(BorderStroke(0.75.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Melodist TV",
                    color = MelodistColors.TextMuted,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 标题、副标题与控制区通过 Crossfade 顺畅过渡
        Crossfade(
            targetState = isPlayerMode,
            animationSpec = tween(durationMillis = 250),
            label = "PlaylistLeftPanelCrossfade",
        ) { inPlayer ->
            if (inPlayer) {
                Column(modifier = Modifier.offset(x = 8.dp)) {
                    Text(
                        text = activePlayingSong?.name?.ifBlank { "未选择曲目" } ?: "正在载入曲目...",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (activePlayingSong != null) "${activePlayingSong.singer} · ${activePlayingSong.album}" else "正在同步曲目信息...",
                        fontSize = 15.sp,
                        color = MelodistColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (playbackErrorMessage != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = playbackErrorMessage,
                            fontSize = 13.sp,
                            color = Color(0xFFFF6B6B),
                            maxLines = 1,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.offset(x = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = resolvedTitle,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MelodistColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = displaySubtitle,
                            fontSize = 13.sp,
                            color = MelodistColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Button(
                        onClick = onPlayAllClick,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .focusRequester(playAllRequester)
                                .onFocusChanged { onPlayAllFocusChanged(it.isFocused) }
                                .then(
                                    if (hasSongs) {
                                        Modifier.focusProperties { right = firstSongRequester }
                                    } else {
                                        Modifier
                                    },
                                ),
                        shape =
                            ButtonDefaults.shape(
                                shape = MelodistShapes.CardCorner,
                                focusedShape = MelodistShapes.CardCorner,
                            ),
                        colors =
                            ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                focusedContainerColor = MelodistColors.AccentGreen,
                                contentColor = Color.White,
                                focusedContentColor = Color.Black,
                            ),
                        border =
                            ButtonDefaults.border(
                                border =
                                    Border(
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                        shape = MelodistShapes.CardCorner,
                                    ),
                                focusedBorder =
                                    Border(
                                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                        shape = MelodistShapes.CardCorner,
                                    ),
                            ),
                        scale = ButtonDefaults.scale(focusedScale = 1.0f),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "播放全部",
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "播放全部",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistFilterChipsBar(
    categoryId: String,
    userPlaylists: List<Playlist>,
    selectedPlaylistIndex: Int,
    onSelectPlaylistIndex: (Int) -> Unit,
    userAlbums: List<Album>,
    selectedAlbumIndex: Int,
    onSelectAlbumIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categoryId == "playlists" && userPlaylists.isNotEmpty()) {
        LazyRow(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(userPlaylists) { pIdx, pl ->
                PlaylistFilterChip(
                    text = "${pl.name} (${pl.songCount})",
                    isSelected = pIdx == selectedPlaylistIndex,
                    onClick = { onSelectPlaylistIndex(pIdx) },
                )
            }
        }
    } else if (categoryId == "collections" && userAlbums.isNotEmpty()) {
        LazyRow(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(userAlbums) { aIdx, alb ->
                PlaylistFilterChip(
                    text = "${alb.title} · ${alb.artist}",
                    isSelected = aIdx == selectedAlbumIndex,
                    onClick = { onSelectAlbumIndex(aIdx) },
                )
            }
        }
    }
}

@Composable
fun PlaylistFilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(MelodistShapes.PillCorner),
        colors =
            ButtonDefaults.colors(
                containerColor =
                    if (isSelected) {
                        MelodistColors.AccentGreen.copy(alpha = 0.25f)
                    } else {
                        MelodistColors.ContainerDarkSecondary
                    },
                focusedContainerColor = Color.White,
                contentColor = if (isSelected) MelodistColors.AccentGreen else MelodistColors.TextSecondary,
                focusedContentColor = Color.Black,
            ),
        border =
            ButtonDefaults.border(
                border =
                    Border(
                        border =
                            BorderStroke(
                                1.dp,
                                if (isSelected) MelodistColors.AccentGreen else Color.White.copy(alpha = 0.1f),
                            ),
                        shape = MelodistShapes.PillCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.PillCorner,
                    ),
            ),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
