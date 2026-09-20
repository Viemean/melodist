package org.melodist.mobile.ui.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.model.RecommendShelf
import org.melodist.model.Song

private val ShelfCardShape = RoundedCornerShape(12.dp)

@Composable
fun RecommendShelfSection(
    shelves: List<RecommendShelf>,
    onSongClick: (song: Song, shelfSongs: List<Song>) -> Unit,
    modifier: Modifier = Modifier,
    onPlayShelf: ((shelf: RecommendShelf) -> Unit)? = null,
) {
    if (shelves.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        shelves.forEach { shelf ->
            RecommendShelfItem(
                shelf = shelf,
                onSongClick = { song -> onSongClick(song, shelf.songs) },
            )
        }
    }
}

@Composable
private fun RecommendShelfItem(
    shelf: RecommendShelf,
    onSongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        val cleanTitle =
            androidx.compose.runtime.remember(shelf.title) {
                shelf.title.replace(Regex("[💗❤️💖💕💓💘🤍🖤🤎💜💙💚💛🧡♥]"), "").trim()
            }

        // 货架头部标题
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = cleanTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 横向滚动卡片列表
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(
                items = shelf.songs,
                key = { index, song -> "${shelf.title}_${song.songId}_${song.songMid}_$index" },
            ) { _, song ->
                RecommendSongCard(
                    song = song,
                    onClick = { onSongClick(song) },
                )
            }
        }
    }
}

@Composable
private fun RecommendSongCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        shape = ShelfCardShape,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        modifier = modifier.width(124.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(108.dp)
                        .clip(RoundedCornerShape(8.dp)),
            ) {
                AlbumArtImage(
                    coverUrl = song.thumbnailCoverUrl,
                    candidates = song.thumbnailCandidates,
                    contentDescription = song.name,
                    shape = RoundedCornerShape(8.dp),
                    elevation = 0.dp,
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.10f)),
                    placeholderIconSize = 28.dp,
                    modifier = Modifier.matchParentSize(),
                )

                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = CircleShape,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = song.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = song.singer.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
