package org.melodist.mobile.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.melodist.api.UserSession
import org.melodist.data.ListeningStatsManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.model.ListeningChartPoint
import org.melodist.model.ListeningHighlightItem
import org.melodist.model.ListeningMonthlySongItem
import org.melodist.model.ListeningRankItem
import org.melodist.model.ListeningReportData
import org.melodist.model.ListeningReportPeriod
import org.melodist.model.ListeningSingerStat
import org.melodist.model.ListeningTextBlock
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

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

            // 2. 日期切换导航（< 2026年10月12日 >）
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
) {
    val currentIndex = availableDates.indexOf(currentDate).takeIf { it >= 0 } ?: 0
    val hasPrev = currentIndex < availableDates.size - 1
    val hasNext = currentIndex > 0

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
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
}

@Composable
private fun ListeningOverviewCard(
    report: ListeningReportData,
    selectedSlot: String,
    onSelectSlot: (String) -> Unit = {},
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (report.period) {
                ListeningReportPeriod.Day -> DayOverviewContent(report)
                ListeningReportPeriod.Week -> WeekOverviewContent(report)
                ListeningReportPeriod.Month -> MonthOverviewContent(report, selectedSlot, onSelectSlot)
                ListeningReportPeriod.Year -> YearOverviewContent(report, selectedSlot, onSelectSlot)
            }

            if (report.topSingers.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                TopSingersList(singers = report.topSingers)
            }
        }
    }
}

@Composable
private fun DayOverviewContent(report: ListeningReportData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = formatDurationDisplay(report.listenTimeSec, report.listenCnt),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (report.title.isNotBlank()) report.title else "今日听歌时长",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (report.activeHour > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${report.activeHour} 点",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "最活跃时段",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (report.chartPoints.isNotEmpty()) {
            Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                ListeningClockDial(
                    chartPoints = report.chartPoints,
                    activeHour = report.activeHour,
                )
            }
        }
    }
}

@Composable
private fun WeekOverviewContent(report: ListeningReportData) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = formatDurationDisplay(report.listenTimeSec, report.listenCnt),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
            val diffText =
                if (report.percent > 0f) {
                    "本周日均听歌时长比上周多 ${(report.percent * 100).toInt()}%"
                } else {
                    "本周日均听歌时长"
                }
            Text(
                text = diffText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (report.chartPoints.isNotEmpty()) {
            ListeningWeekBarChart(
                chartPoints = report.chartPoints,
                modifier = Modifier.fillMaxWidth().height(110.dp),
            )
        }
    }
}

@Composable
private fun MonthOverviewContent(
    report: ListeningReportData,
    selectedSlot: String,
    onSelectSlot: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = report.title.ifBlank { "本月听歌首数" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${report.listenCnt} 首",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (report.percent > 0f) {
                val trendText =
                    when (report.upOrDown) {
                        1 -> "相比上月增加 ${(report.percent * 100).toInt()}%"
                        2 -> "相比上月减少 ${(report.percent * 100).toInt()}%"
                        else -> "相比上月 ${(report.percent * 100).toInt()}%"
                    }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                ) {
                    Text(
                        text = trendText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        if (report.chartPoints.isNotEmpty()) {
            ListeningTrendChart(
                chartPoints = report.chartPoints,
                selectedSlot = selectedSlot,
                onSelectPoint = onSelectSlot,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
        }
    }
}

@Composable
private fun YearOverviewContent(
    report: ListeningReportData,
    selectedSlot: String,
    onSelectSlot: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "年度听歌时长",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDurationDisplay(report.listenTimeSec, report.listenCnt),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (report.listenCnt > 0) {
                Text(
                    text = "${report.listenCnt} 首",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (report.chartPoints.isNotEmpty()) {
            ListeningTrendChart(
                chartPoints = report.chartPoints,
                selectedSlot = selectedSlot,
                onSelectPoint = onSelectSlot,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
        }
    }
}

@Composable
private fun TopSingersList(singers: List<ListeningSingerStat>) {
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
private fun ListeningWeekBarChart(
    chartPoints: List<ListeningChartPoint>,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val maxValue = (chartPoints.maxOfOrNull { it.value } ?: 1L).coerceAtLeast(1L)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            chartPoints.forEach { pt ->
                val fraction = (pt.value.toFloat() / maxValue.toFloat()).coerceIn(0.04f, 1f)
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .width(10.dp)
                                    .fillMaxHeight(fraction)
                                    .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                    .background(if (pt.value > 0L) primaryColor else trackColor),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            chartPoints.forEach { pt ->
                Text(
                    text = pt.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (pt.value > 0L) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ListeningTrendChart(
    chartPoints: List<ListeningChartPoint>,
    selectedSlot: String,
    onSelectPoint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val maxVal = (chartPoints.maxOfOrNull { it.value } ?: 1L).coerceAtLeast(1L)
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackDotColor = primaryColor.copy(alpha = 0.35f)
    val curveBrush =
        Brush.horizontalGradient(
            colors = listOf(primaryColor.copy(alpha = 0.65f), primaryColor),
        )

    var pointOffsets by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Column(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(chartPoints) {
                        detectTapGestures { tapOffset ->
                            if (pointOffsets.isEmpty()) return@detectTapGestures
                            val closestIndex =
                                pointOffsets.indices.minByOrNull { idx ->
                                    kotlin.math.abs(pointOffsets[idx].x - tapOffset.x)
                                } ?: return@detectTapGestures
                            onSelectPoint(chartPoints[closestIndex].slot)
                        }
                    },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (chartPoints.size < 2) return@Canvas
                val w = size.width
                val h = size.height
                val paddingX = 24.dp.toPx()
                val step = (w - paddingX * 2f) / (chartPoints.size - 1)

                val points =
                    chartPoints.mapIndexed { index, pt ->
                        val x = paddingX + index * step
                        val y = h - ((pt.value.toFloat() / maxVal.toFloat()) * (h - 36.dp.toPx())) - 24.dp.toPx()
                        Offset(x, y)
                    }
                pointOffsets = points

                // 平滑三次贝塞尔曲线构建，采用自适应切线平滑过渡
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 0 until points.size - 1) {
                        val p0 = points[i]
                        val p1 = points[i + 1]
                        val dx = (p1.x - p0.x) * 0.45f
                        val c1x = p0.x + dx
                        val c1y = p0.y
                        val c2x = p1.x - dx
                        val c2y = p1.y
                        cubicTo(c1x, c1y, c2x, c2y, p1.x, p1.y)
                    }
                }

                drawPath(
                    path = path,
                    brush = curveBrush,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )

                // 绘制各点位指示器
                points.forEachIndexed { idx, ptOffset ->
                    val pt = chartPoints[idx]
                    val isSelected = pt.slot == selectedSlot
                    if (isSelected) {
                        drawCircle(
                            color = primaryColor,
                            radius = 7.dp.toPx(),
                            center = ptOffset,
                            style = Stroke(width = 3.dp.toPx()),
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 4.dp.toPx(),
                            center = ptOffset,
                        )
                    } else {
                        drawCircle(
                            color = trackDotColor,
                            radius = 3.5.dp.toPx(),
                            center = ptOffset,
                        )
                    }
                }
            }

            // 选中的点位下方悬挂小头像
            val selectedIndex = chartPoints.indexOfFirst { it.slot == selectedSlot }
            if (selectedIndex >= 0 && selectedIndex < pointOffsets.size) {
                val selectedPt = chartPoints[selectedIndex]
                val offset = pointOffsets[selectedIndex]
                if (selectedPt.avatarUrl.isNotBlank()) {
                    val avatarLeftDp = with(density) { (offset.x - 14.dp.toPx()).toDp() }
                    val avatarTopDp = with(density) { (offset.y + 10.dp.toPx()).toDp() }
                    AsyncImage(
                        model = selectedPt.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .offset(x = avatarLeftDp, y = avatarTopDp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(1.5.dp)
                                .clip(CircleShape),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            chartPoints.forEach { pt ->
                val isSelected = pt.slot == selectedSlot
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelectPoint(pt.slot) }
                            .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = pt.text,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicGeneCard(textBlock: ListeningTextBlock) {
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
private fun HighlightsSection(
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
private fun HighlightCard(
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
private fun Md3DynamicClock(
    hour: Int,
    minute: Int,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val outlineColor = MaterialTheme.colorScheme.primary
    val hourHandColor = MaterialTheme.colorScheme.primary
    val minuteHandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    val centerDotColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val strokeWidth = 3.2.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f - strokeWidth / 2f

        drawCircle(
            color = containerColor,
            radius = radius,
            center = center,
        )
        drawCircle(
            color = outlineColor,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth),
        )

        val tickRadius = radius - 4.dp.toPx()
        val tickDotColor = outlineColor.copy(alpha = 0.45f)
        for (i in 0 until 4) {
            val angle = Math.toRadians((i * 90.0) - 90.0)
            val dotCenter =
                Offset(
                    x = center.x + (tickRadius * cos(angle)).toFloat(),
                    y = center.y + (tickRadius * sin(angle)).toFloat(),
                )
            drawCircle(
                color = tickDotColor,
                radius = 1.2.dp.toPx(),
                center = dotCenter,
            )
        }

        val h = hour % 12
        val hourAngle = Math.toRadians(((h + minute / 60f) * 30f - 90f).toDouble())
        val minuteAngle = Math.toRadians((minute * 6f - 90f).toDouble())

        val hourLen = radius * 0.52f
        val hourEnd =
            Offset(
                x = center.x + (hourLen * cos(hourAngle)).toFloat(),
                y = center.y + (hourLen * sin(hourAngle)).toFloat(),
            )
        drawLine(
            color = hourHandColor,
            start = center,
            end = hourEnd,
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )

        val minLen = radius * 0.72f
        val minEnd =
            Offset(
                x = center.x + (minLen * cos(minuteAngle)).toFloat(),
                y = center.y + (minLen * sin(minuteAngle)).toFloat(),
            )
        drawLine(
            color = minuteHandColor,
            start = center,
            end = minEnd,
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )

        drawCircle(
            color = centerDotColor,
            radius = 2.6.dp.toPx(),
            center = center,
        )
    }
}

private fun parseClockTime(timeStr: String): Pair<Int, Int>? {
    val regex = Regex("""(\d{1,2}):(\d{2})""")
    val match = regex.find(timeStr) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return hour to minute
}

@Composable
private fun ListeningClockDial(
    chartPoints: List<ListeningChartPoint>,
    activeHour: Int,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f - 4.dp.toPx()

        // 绘制表盘环底
        drawCircle(
            color = trackColor,
            radius = radius,
            center = center,
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
        )

        // 高亮活跃槽位
        chartPoints.forEach { pt ->
            val hour = pt.slot.toIntOrNull() ?: return@forEach
            if (pt.value > 0L) {
                val angle = (hour / 24f) * 360f - 90f
                val sweep = 360f / 24f
                val isPeak = hour == activeHour
                drawArc(
                    color = if (isPeak) primaryColor else primaryColor.copy(alpha = 0.5f),
                    startAngle = angle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = if (isPeak) 8.dp.toPx() else 6.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun ListeningRankSongRow(
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

@Composable
private fun EmptyStatsCard() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().height(120.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "暂无收听统计数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDurationDisplay(sec: Long, cnt: Int): String {
    if (sec <= 0L && cnt > 0) {
        return "$cnt 首"
    }
    val minutes = sec / 60
    if (minutes < 60) {
        return "$minutes 分钟"
    }
    val hours = minutes / 60
    val remainMin = minutes % 60
    return if (remainMin > 0) "$hours 小时 $remainMin 分钟" else "$hours 小时"
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

@Composable
private fun MonthlyTopSongsCard(
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
private fun MonthlySongItem(
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
