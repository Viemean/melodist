package org.melodist.mobile.ui.stats.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.melodist.model.ListeningHighlightItem
import org.melodist.model.ListeningMonthlySongItem
import org.melodist.model.ListeningReportPeriod
import org.melodist.model.ListeningTextBlock
import org.melodist.model.Song

/**
 * 听歌数据音乐基因与品味解读卡片。
 *
 * @param textBlock 包含高亮关键词与品味描述的文本块
 */
@Composable
internal fun MusicGeneCard(textBlock: ListeningTextBlock) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val annotatedString =
            buildAnnotatedString {
                val fullText = textBlock.text
                var currentIndex = 0
                val primaryColor = MaterialTheme.colorScheme.primary

                // 寻找所有关键词位置并高亮
                val sortedKeywords = textBlock.colorWords.filter { it.isNotBlank() }.distinct()
                val ranges = mutableListOf<Pair<IntRange, String>>()
                sortedKeywords.forEach { kw ->
                    var idx = fullText.indexOf(kw, 0)
                    while (idx >= 0) {
                        ranges.add((idx until idx + kw.length) to kw)
                        idx = fullText.indexOf(kw, idx + kw.length)
                    }
                }
                ranges.sortBy { it.first.first }

                ranges.forEach { (range, _) ->
                    if (range.first >= currentIndex) {
                        append(fullText.substring(currentIndex, range.first))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primaryColor)) {
                            append(fullText.substring(range.first, range.last + 1))
                        }
                        currentIndex = range.last + 1
                    }
                }
                if (currentIndex < fullText.length) {
                    append(fullText.substring(currentIndex))
                }
            }

        Text(
            text = annotatedString,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 22.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
internal fun HighlightsSection(
    highlights: List<ListeningHighlightItem>,
    period: ListeningReportPeriod,
) {
    val orderedHighlights = remember(highlights) {
        val list = highlights.toMutableList()
        val continuousIdx = list.indexOfFirst { it.title.contains("连续") }
        val timeIdx = list.indexOfFirst { it.type == 3 || it.title.contains("深夜") }
        if (continuousIdx != -1 && timeIdx != -1 && timeIdx < continuousIdx) {
            val temp = list[continuousIdx]
            list[continuousIdx] = list[timeIdx]
            list[timeIdx] = temp
        }
        list
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "本${period.label}高光时刻",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isWide = maxWidth >= 600.dp || orderedHighlights.size <= 2

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    orderedHighlights.forEach { h ->
                        HighlightCard(
                            highlight = h,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            } else {
                val chunked = orderedHighlights.chunked(2)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    chunked.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowItems.forEach { h ->
                                HighlightCard(
                                    highlight = h,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            if (rowItems.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HighlightCard(
    highlight: ListeningHighlightItem,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = highlight.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = highlight.subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val clockTime = parseClockTime(highlight.subtitle)
                val isTimeClock = highlight.type == 3 || (clockTime != null && (highlight.title.contains("深夜") || highlight.title.contains("听歌") || highlight.title.contains("早晨")))

                if (isTimeClock) {
                    val (hour, minute) = clockTime ?: (4 to 42)
                    Md3DynamicClock(
                        hour = hour,
                        minute = minute,
                        modifier = Modifier.size(44.dp),
                    )
                } else if (highlight.iconUrls.isNotEmpty()) {
                    Box(modifier = Modifier.height(40.dp).fillMaxWidth()) {
                        highlight.iconUrls.take(3).forEachIndexed { idx, iconUrl ->
                            AsyncImage(
                                model = iconUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .offset(x = (idx * 24).dp)
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(1.5.dp)
                                        .clip(CircleShape),
                            )
                        }
                    }
                } else if (highlight.coverUrl.isNotBlank()) {
                    Box(modifier = Modifier.wrapContentSize()) {
                        AsyncImage(
                            model = highlight.coverUrl,
                            contentDescription = highlight.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        if (highlight.repeatCnt > 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp),
                            ) {
                                Text(
                                    text = "x${highlight.repeatCnt}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (highlight.tips.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = highlight.tips,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun MonthlyTopSongsCard(
    monthlySongs: List<ListeningMonthlySongItem>,
    onPlayPlaylist: (songs: List<Song>, startIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playableSongs = remember(monthlySongs) {
        monthlySongs.mapNotNull { it.song }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "每月最爱歌曲",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(
                    items = monthlySongs,
                    key = { "${it.month}_${it.songMid}" },
                ) { item ->
                    MonthlySongItem(
                        item = item,
                        onClick = {
                            val targetSong = item.song ?: return@MonthlySongItem
                            val targetIndex = playableSongs.indexOfFirst { it.songMid == targetSong.songMid }.takeIf { it >= 0 } ?: 0
                            onPlayPlaylist(playableSongs, targetIndex)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MonthlySongItem(
    item: ListeningMonthlySongItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .width(88.dp)
                .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.songName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(topStart = 4.dp, bottomEnd = 4.dp),
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                Text(
                    text = "${item.month}月",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }

        Text(
            text = item.songName,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
