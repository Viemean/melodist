package org.melodist.mobile.ui.stats.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.melodist.model.ListeningChartPoint
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun ListeningWeekBarChart(
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
internal fun ListeningTrendChart(
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
                val path =
                    Path().apply {
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
internal fun Md3DynamicClock(
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

internal fun parseClockTime(timeStr: String): Pair<Int, Int>? {
    val regex = Regex("""(\d{1,2}):(\d{2})""")
    val match = regex.find(timeStr) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return hour to minute
}

@Composable
internal fun ListeningClockDial(
    chartPoints: List<ListeningChartPoint>,
    activeHour: Int,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val containerColor = primaryColor.copy(alpha = 0.15f)
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val tickDotColor = primaryColor.copy(alpha = 0.45f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val strokeWidth = 3.6.dp.toPx()
        val radius = size.minDimension / 2f - strokeWidth / 2f - 2.dp.toPx()

        // 表盘背景填充与外圈轮廓
        drawCircle(
            color = containerColor,
            radius = radius,
            center = center,
        )
        drawCircle(
            color = trackColor,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )

        // 活跃槽位弧段
        val peakH = (if (activeHour in 0..23) activeHour else 0) % 12
        val activeSlots12 = mutableSetOf<Int>()
        chartPoints.forEach { pt ->
            if (pt.value > 0L) {
                pt.slot.toIntOrNull()?.let { activeSlots12.add(it % 12) }
            }
        }

        val sweep = 26f
        val sortedSlots = activeSlots12.sortedBy { it == peakH }
        sortedSlots.forEach { h ->
            val isPeak = h == peakH
            val centerAngle = (h * 30f) - 90f
            val startAngle = centerAngle - (sweep / 2f)
            drawArc(
                color = if (isPeak) primaryColor else primaryColor.copy(alpha = 0.5f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = if (isPeak) strokeWidth * 1.5f else strokeWidth, cap = StrokeCap.Round),
            )
        }

        // 四象限刻度点
        val tickRadius = radius - 6.dp.toPx()
        for (i in 0 until 4) {
            val angle = Math.toRadians((i * 90.0) - 90.0)
            val dotCenter =
                Offset(
                    x = center.x + (tickRadius * cos(angle)).toFloat(),
                    y = center.y + (tickRadius * sin(angle)).toFloat(),
                )
            drawCircle(
                color = tickDotColor,
                radius = 1.6.dp.toPx(),
                center = dotCenter,
            )
        }

        // 时针与分针
        val effectiveHour = if (activeHour in 0..23) activeHour else 0
        val h = effectiveHour % 12
        val hourAngle = Math.toRadians((h * 30.0 - 90.0))
        val minuteAngle = Math.toRadians(-90.0)

        val hourLen = radius * 0.48f
        val hourEnd =
            Offset(
                x = center.x + (hourLen * cos(hourAngle)).toFloat(),
                y = center.y + (hourLen * sin(hourAngle)).toFloat(),
            )
        drawLine(
            color = primaryColor,
            start = center,
            end = hourEnd,
            strokeWidth = 3.2.dp.toPx(),
            cap = StrokeCap.Round,
        )

        val minLen = radius * 0.68f
        val minEnd =
            Offset(
                x = center.x + (minLen * cos(minuteAngle)).toFloat(),
                y = center.y + (minLen * sin(minuteAngle)).toFloat(),
            )
        drawLine(
            color = primaryColor.copy(alpha = 0.85f),
            start = center,
            end = minEnd,
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // 中心轴点
        drawCircle(
            color = primaryColor,
            radius = 3.dp.toPx(),
            center = center,
        )
    }
}
