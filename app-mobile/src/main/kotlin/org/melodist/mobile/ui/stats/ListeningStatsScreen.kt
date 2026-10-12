package org.melodist.mobile.ui.stats

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Tune
import org.melodist.api.UserSession
import org.melodist.data.ListeningStatsManager
import org.melodist.mobile.ui.components.MusicPreferenceBottomSheet
import org.melodist.mobile.ui.stats.components.*
import org.melodist.model.ListeningReportPeriod
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

/**
 * 用户听歌数据报告与周期足迹全屏展示界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningStatsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onPlayPlaylist: (songs: List<Song>, startIndex: Int) -> Unit = { songs, startIndex ->
        if (songs.isNotEmpty()) {
            PlaybackManager.setPlaylist(songs, startIndex = startIndex)
        }
    },
) {
    val coroutineScope = rememberCoroutineScope()
    val statsData by ListeningStatsManager.currentStats.collectAsState()
    val isLoading by ListeningStatsManager.isLoading.collectAsState()
    val selectedPeriod by ListeningStatsManager.selectedPeriod.collectAsState()
    val selectedDate by ListeningStatsManager.selectedDate.collectAsState()
    val isLoggedIn = UserSession.isLoggedIn
    var showPreferenceSheet by remember { mutableStateOf(false) }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn && statsData == null) {
            ListeningStatsManager.refresh(force = false)
        }
    }

    if (!isLoggedIn) {
        Box(
            modifier = modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "登录账号后即可同步查看云端听歌数据与足迹",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = {
            coroutineScope.launch {
                ListeningStatsManager.refresh(force = true)
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 周期切换器（日 / 周 / 月 / 年）
            item(key = "period_selector") {
                PeriodSegmentedTabs(
                    selectedPeriod = selectedPeriod,
                    onPeriodSelect = { period ->
                        coroutineScope.launch {
                            ListeningStatsManager.setPeriod(period)
                        }
                    },
                )
            }

            // 2. 日期切换导航（< 2026年10月12日 >）及右侧偏好调节入口
            statsData?.let { report ->
                if (report.availableDates.isNotEmpty()) {
                    item(key = "date_navigator") {
                        DateNavigatorBar(
                            currentDate = selectedDate ?: report.dateKey,
                            availableDates = report.availableDates,
                            period = selectedPeriod,
                            onDateSelect = { newDate ->
                                coroutineScope.launch {
                                    ListeningStatsManager.setDate(newDate)
                                }
                            },
                            onOpenPreference = { showPreferenceSheet = true },
                        )
                    }
                }
            }

            // 3. 听歌核心指标卡片
            item(key = "stats_hero_card") {
                statsData?.let { report ->
                    ListeningOverviewCard(
                        report = report,
                        selectedSlot = selectedDate ?: report.dateKey,
                        onSelectSlot = { newSlot ->
                            coroutineScope.launch {
                                ListeningStatsManager.setDate(newSlot)
                            }
                        },
                    )
                } ?: run {
                    if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        EmptyStatsCard()
                    }
                }
            }

            // 4. 音乐基因卡片
            statsData?.textBlock?.let { tb ->
                item(key = "music_gene_card") {
                    MusicGeneCard(textBlock = tb)
                }
            }

            // 5. 高光时刻（月 / 年）
            statsData?.let { report ->
                if (report.highlights.isNotEmpty()) {
                    item(key = "highlights_section") {
                        HighlightsSection(highlights = report.highlights, period = selectedPeriod)
                    }
                }

                if (selectedPeriod == ListeningReportPeriod.Year && report.monthlyTopSongs.isNotEmpty()) {
                    item(key = "monthly_top_songs_section") {
                        MonthlyTopSongsCard(
                            monthlySongs = report.monthlyTopSongs,
                            onPlayPlaylist = onPlayPlaylist,
                        )
                    }
                }
            }

            // 4. 收听歌曲排行榜
            statsData?.let { report ->
                if (report.rankSongs.isNotEmpty()) {
                    item(key = "rank_section_header") {
                        Text(
                            text = "本${selectedPeriod.label}听歌排行",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }

                    val playlistSongs = report.rankSongs.map { it.song }

                    itemsIndexed(
                        items = report.rankSongs,
                        key = { _, item -> "${item.song.songId}_${item.rankNo}" },
                    ) { index, rankItem ->
                        ListeningRankSongRow(
                            item = rankItem,
                            onClick = { onPlayPlaylist(playlistSongs, index) },
                        )
                    }
                }
            }
        }
    }

    if (showPreferenceSheet) {
        MusicPreferenceBottomSheet(
            onDismissRequest = { showPreferenceSheet = false },
        )
    }
}

@Composable
private fun PeriodSegmentedTabs(
    selectedPeriod: ListeningReportPeriod,
    onPeriodSelect: (ListeningReportPeriod) -> Unit,
) {
    val entries = remember { ListeningReportPeriod.entries }
    val selectedIndex = entries.indexOf(selectedPeriod).coerceAtLeast(0)

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier.padding(4.dp).fillMaxSize(),
        ) {
            val count = entries.size
            val spacing = 4.dp
            val totalSpacing = spacing * (count - 1)
            val tabWidth = (maxWidth - totalSpacing) / count

            val animatedIndex by animateFloatAsState(
                targetValue = selectedIndex.toFloat(),
                animationSpec =
                    spring(
                        dampingRatio = 0.82f,
                        stiffness = 380f,
                    ),
                label = "segmented_indicator_index",
            )

            // 单滑块指示器胶囊
            Box(
                modifier =
                    Modifier
                        .offset(x = (tabWidth + spacing) * animatedIndex)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary),
            )

            // 选项文本与点击响应
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                entries.forEach { period ->
                    val selected = period == selectedPeriod
                    val textColor by animateColorAsState(
                        targetValue =
                            if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        animationSpec = tween(durationMillis = 200),
                        label = "segmented_text_color",
                    )

                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { onPeriodSelect(period) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = period.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateNavigatorBar(
    currentDate: String,
    availableDates: List<String>,
    period: ListeningReportPeriod,
    onDateSelect: (String) -> Unit,
    onOpenPreference: () -> Unit,
) {
    val currentIndex = availableDates.indexOf(currentDate).takeIf { it >= 0 } ?: 0
    val hasPrev = currentIndex < availableDates.size - 1
    val hasNext = currentIndex > 0

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (hasPrev) onDateSelect(availableDates[currentIndex + 1])
                },
                enabled = hasPrev,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "前一周期",
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatDateLabel(currentDate, period),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (hasNext) onDateSelect(availableDates[currentIndex - 1])
                },
                enabled = hasNext,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "后一周期",
                )
            }
        }

        IconButton(
            onClick = onOpenPreference,
            modifier = Modifier.align(Alignment.CenterEnd).size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "调节听歌偏好",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDateLabel(date: String, period: ListeningReportPeriod): String {
    if (date.isBlank()) return "最新"
    return when (period) {
        ListeningReportPeriod.Day -> {
            if (date.length == 8) {
                "${date.substring(4, 6)}月${date.substring(6, 8)}日"
            } else date
        }
        ListeningReportPeriod.Month -> {
            if (date.length >= 6) {
                "${date.substring(0, 4)}年${date.substring(4, 6)}月"
            } else date
        }
        ListeningReportPeriod.Year -> {
            "${date.take(4)}年"
        }
        ListeningReportPeriod.Week -> {
            if (date.length == 8) {
                "${date.substring(4, 6)}月${date.substring(6, 8)}日当周"
            } else date
        }
    }
}
