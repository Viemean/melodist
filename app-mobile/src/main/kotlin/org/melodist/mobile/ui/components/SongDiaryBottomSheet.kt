package org.melodist.mobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.getSongDiary
import org.melodist.api.getSongListenRecords
import org.melodist.model.Song
import org.melodist.model.SongDiaryData
import org.melodist.model.SongListenRecordItem
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class SongDiaryViewMode {
    DIARY,
    RECORDS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDiaryBottomSheet(
    song: Song,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }

    var viewMode by remember { mutableStateOf(SongDiaryViewMode.DIARY) }

    var isLoadingDiary by remember { mutableStateOf(true) }
    var diaryData by remember { mutableStateOf<SongDiaryData?>(null) }

    var isLoadingRecords by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf<List<SongListenRecordItem>>(emptyList()) }
    var nextCursor by remember { mutableStateOf("") }
    var hasMoreRecords by remember { mutableStateOf(true) }

    LaunchedEffect(song.songMid) {
        isLoadingDiary = true
        val result = apiService.getSongDiary(song.songMid)
        diaryData = result
        isLoadingDiary = false
    }

    fun loadRecords(cursor: String? = null) {
        if (isLoadingRecords) return
        coroutineScope.launch {
            isLoadingRecords = true
            val page = apiService.getSongListenRecords(song.songMid, cursor = cursor)
            if (page != null) {
                if (cursor == null) {
                    records = page.items
                } else {
                    records = records + page.items
                }
                nextCursor = page.nextCursor
                hasMoreRecords = page.nextCursor.isNotBlank() && page.nextCursor != "0"
            } else {
                hasMoreRecords = false
            }
            isLoadingRecords = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
        ) {
            // Header 导航栏
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (viewMode == SongDiaryViewMode.RECORDS) {
                    IconButton(onClick = { viewMode = SongDiaryViewMode.DIARY }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                    Text(
                        text = "倒带·日记",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.size(48.dp))
                } else {
                    Text(
                        text = "单曲倒带日记",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    if (diaryData?.hasListenRecord == true) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            modifier =
                                Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                                    viewMode = SongDiaryViewMode.RECORDS
                                    if (records.isEmpty()) {
                                        loadRecords(null)
                                    }
                                },
                        ) {
                            Text(
                                text = "播放记录",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 歌曲信息条目
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtImage(
                    coverUrl = song.thumbnailCoverUrl.ifBlank { diaryData?.coverUrl.orEmpty() },
                    candidates = song.thumbnailCandidates,
                    songMid = song.songMid,
                    contentDescription = song.name,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.name.ifBlank { diaryData?.title.orEmpty() },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = song.singer.ifBlank { diaryData?.subTitle.orEmpty() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedContent(
                targetState = viewMode,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "view_mode_transition",
            ) { mode ->
                if (mode == SongDiaryViewMode.DIARY) {
                    if (isLoadingDiary) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val diary = diaryData
                        if (diary == null) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(160.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "暂无该单曲的倒带日记数据",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // 1. 红心时间 与 上次收听（两列）
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        DiarySmallCard(
                                            title = "红心时间",
                                            mainText = diary.favDate.ifBlank { "--" },
                                            bottomText = diary.favBottom.ifBlank { "记忆中流转曾经的瞬间" },
                                            modifier = Modifier.weight(1f),
                                        )

                                        DiarySmallCard(
                                            title = "上次收听",
                                            mainText = diary.lastLsnTime.ifBlank { "--" },
                                            bottomText = diary.lastLsnDate.ifBlank { "近期收听" },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }

                                // 2. 首次邂逅
                                if (diary.firstLsnTime.isNotBlank()) {
                                    item {
                                        Card(
                                            shape = RoundedCornerShape(16.dp),
                                            colors =
                                                CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                                ),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(
                                                        text = "首次邂逅",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Text(
                                                        text = diary.firstLsnTimeCn.ifBlank { "初次聆听" },
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                    Text(
                                                        text = "${diary.firstLsnTime} · 播放来源 ${diary.firstLsnDevice}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }

                                                if (diary.firstLsnDayCnt > 0) {
                                                    Column(horizontalAlignment = Alignment.End) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Nightlight,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                                            modifier = Modifier.size(28.dp),
                                                        )
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text(
                                                            text = "相遇天数 ${diary.firstLsnDayCnt}天",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 3. 近年播放走势
                                if (diary.totalLsnCounts.isNotEmpty()) {
                                    item {
                                        Card(
                                            shape = RoundedCornerShape(16.dp),
                                            colors =
                                                CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                                ),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Column {
                                                        Text(
                                                            text = "近年播放",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                        Text(
                                                            text = diary.totalLsnCountText,
                                                            style = MaterialTheme.typography.headlineSmall,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            color = MaterialTheme.colorScheme.primary,
                                                        )
                                                    }
                                                    if (diary.totalLsnBottom.isNotBlank()) {
                                                        Text(
                                                            text = diary.totalLsnBottom,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }

                                                DiaryMiniTrendChart(
                                                    years = diary.totalLsnYears,
                                                    counts = diary.totalLsnCounts,
                                                    modifier = Modifier.fillMaxWidth().height(80.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                // 4. 近半年播放
                                if (diary.recentLsnMonths.isNotEmpty()) {
                                    item {
                                        Card(
                                            shape = RoundedCornerShape(16.dp),
                                            colors =
                                                CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                                ),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(
                                                        text = "近半年播放",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Text(
                                                        text = diary.recentLsnCountText,
                                                        style = MaterialTheme.typography.headlineSmall,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                    if (diary.recentLsnBottom.isNotBlank()) {
                                                        Text(
                                                            text = diary.recentLsnBottom,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }

                                                // 点阵热力网格示意
                                                DiaryHeatDots(
                                                    months = diary.recentLsnMonths,
                                                    modifier = Modifier.size(width = 120.dp, height = 50.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                // 5. 深夜里程碑
                                if (diary.milestoneTime.isNotBlank()) {
                                    item {
                                        Card(
                                            shape = RoundedCornerShape(16.dp),
                                            colors =
                                                CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                                ),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Text(
                                                    text = diary.milestoneTitle.ifBlank { "高光里程" },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Text(
                                                    text = diary.milestoneTime,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                if (diary.milestoneBottom.isNotBlank()) {
                                                    Text(
                                                        text = diary.milestoneBottom,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // RECORDS 播放记录时间轴
                    if (isLoadingRecords && records.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (records.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "暂无可查询的播放记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            itemsIndexed(records) { index, item ->
                                val prevItem = records.getOrNull(index - 1)
                                val currentYear = formatYear(item.timestampSec)
                                val prevYear = prevItem?.let { formatYear(it.timestampSec) }
                                val isFirstOfYear = index == 0 || currentYear != prevYear

                                if (isFirstOfYear) {
                                    YearHeaderNode(yearText = currentYear)
                                }

                                RecordTimelineRow(item = item)
                            }

                            if (hasMoreRecords) {
                                item {
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isLoadingRecords) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        } else {
                                            TextButton(onClick = { loadRecords(nextCursor) }) {
                                                Text("加载更早的记录")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiarySmallCard(
    title: String,
    mainText: String,
    bottomText: String,
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
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = mainText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = bottomText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DiaryMiniTrendChart(
    years: List<String>,
    counts: List<Int>,
    modifier: Modifier = Modifier,
) {
    if (years.isEmpty() || counts.isEmpty()) return
    val primaryColor = MaterialTheme.colorScheme.primary
    val maxCount = (counts.maxOrNull() ?: 1).coerceAtLeast(1)

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val pointCount = counts.size
        if (pointCount < 2) return@Canvas

        val stepX = width / (pointCount - 1)
        val path = Path()

        val points =
            counts.mapIndexed { i, cnt ->
                val x = i * stepX
                val ratio = cnt.toFloat() / maxCount
                val y = height - (ratio * (height - 20.dp.toPx())) - 8.dp.toPx()
                Offset(x, y)
            }

        path.moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }

        drawPath(
            path = path,
            color = primaryColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )

        points.forEach { pt ->
            drawCircle(
                color = primaryColor,
                radius = 3.dp.toPx(),
                center = pt,
            )
        }
    }
}

@Composable
private fun DiaryHeatDots(
    months: List<org.melodist.model.SongDiaryRecentMonth>,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    Canvas(modifier = modifier) {
        val cols = 6
        val rows = 4
        val dotRadius = 2.5.dp.toPx()
        val stepX = size.width / cols
        val stepY = size.height / rows

        for (c in 0 until cols) {
            val monthData = months.getOrNull(c)
            val hasActive = monthData != null && monthData.activeDays.isNotEmpty()
            for (r in 0 until rows) {
                val cx = c * stepX + stepX / 2
                val cy = r * stepY + stepY / 2
                val isLit = hasActive && (r == 0 || (r == 1 && monthData.activeDays.size > 2))
                drawCircle(
                    color = if (isLit) activeColor else inactiveColor,
                    radius = dotRadius,
                    center = Offset(cx, cy),
                )
            }
        }
    }
}

@Composable
private fun YearHeaderNode(yearText: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = yearText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RecordTimelineRow(item: SongListenRecordItem) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        // 左侧时间轴线条与节点
        Box(
            modifier = Modifier.width(16.dp).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )

            Box(
                modifier =
                    Modifier
                        .padding(top = 8.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 右侧文字信息
        Column(
            modifier = Modifier.padding(bottom = 16.dp).weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val dateStr = formatDateTime(item.timestampSec)
            Text(
                text = "$dateStr 在${item.device}上播放",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (item.source.isNotBlank()) {
                Text(
                    text = "来源: ${item.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val zoneUtc8: ZoneId = ZoneId.of("GMT+8")
private val yearFormatter = DateTimeFormatter.ofPattern("yyyy年")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private fun formatYear(timestampSec: Long): String {
    if (timestampSec <= 0L) return "未知年份"
    val dt = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestampSec), zoneUtc8)
    return dt.format(yearFormatter)
}

private fun formatDateTime(timestampSec: Long): String {
    if (timestampSec <= 0L) return ""
    val dt = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestampSec), zoneUtc8)
    return dt.format(dateTimeFormatter)
}
