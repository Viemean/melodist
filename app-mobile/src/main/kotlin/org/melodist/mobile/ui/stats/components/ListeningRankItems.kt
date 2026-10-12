package org.melodist.mobile.ui.stats.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.model.ListeningRankItem
import org.melodist.model.ListeningSingerStat

@Composable
internal fun TopSingersList(singers: List<ListeningSingerStat>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "常听歌手",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        singers.take(3).forEach { singer ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = singer.singerCoverUrl,
                    contentDescription = singer.singerName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.DarkGray),
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = singer.singerName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (singer.listenTimeSec > 0) {
                    Text(
                        text = "${singer.listenTimeSec / 60} 分钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ListeningRankSongRow(
    item: ListeningRankItem,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 排名数字
        Text(
            text = "${item.rankNo}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color =
                if (item.rankNo == 1) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.width(28.dp),
        )

        // 封面
        AlbumArtImage(
            coverUrl = item.song.coverUrl,
            contentDescription = item.song.name,
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(8.dp),
            elevation = 2.dp,
        )

        Spacer(modifier = Modifier.width(12.dp))

        // 歌名与歌手
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.song.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.song.singer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 收听次数
        if (item.listenCnt > 0) {
            Text(
                text = "${item.listenCnt} 次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
