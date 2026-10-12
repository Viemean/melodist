package org.melodist.mobile.ui.stats.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.melodist.model.ListeningReportData
import org.melodist.model.ListeningReportPeriod

@Composable
internal fun ListeningOverviewCard(
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
internal fun DayOverviewContent(report: ListeningReportData) {
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
internal fun WeekOverviewContent(report: ListeningReportData) {
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
internal fun MonthOverviewContent(
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
internal fun YearOverviewContent(
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
internal fun EmptyStatsCard() {
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

internal fun formatDurationDisplay(
    sec: Long,
    cnt: Int,
): String {
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
