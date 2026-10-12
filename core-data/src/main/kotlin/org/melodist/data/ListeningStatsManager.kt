package org.melodist.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.melodist.api.ApiLogger
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getListeningRecordReport
import org.melodist.model.ListeningChartPoint
import org.melodist.model.ListeningReportData
import org.melodist.model.ListeningReportPeriod
import org.melodist.model.Song
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 本地有效播放增量条目。
 */
data class LocalPlaybackDelta(
    val songId: Long,
    val playSec: Long,
    val timestampMs: Long,
    val dateKey: String,
    val hour: Int,
)

/**
 * 用户听歌数据报告与足迹统计缓存管理器。
 */
object ListeningStatsManager {
    private val apiService = MusicApiService()
    private val json = Json { ignoreUnknownKeys = true }
    private val zoneUtc8: ZoneId = ZoneId.of("GMT+8")
    private const val RECENT_DATA_TTL_MS = 5 * 60 * 1000L

    private val _currentStats = MutableStateFlow<ListeningReportData?>(null)
    val currentStats: StateFlow<ListeningReportData?> = _currentStats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedPeriod = MutableStateFlow(ListeningReportPeriod.Day)
    val selectedPeriod: StateFlow<ListeningReportPeriod> = _selectedPeriod.asStateFlow()

    private val _selectedDate = MutableStateFlow<String?>(null)
    val selectedDate: StateFlow<String?> = _selectedDate.asStateFlow()

    private val cache = ConcurrentHashMap<String, ListeningReportData>()
    private val fetchTimeMap = ConcurrentHashMap<String, Long>()
    private val fullAvailableDatesByPeriod = ConcurrentHashMap<ListeningReportPeriod, List<String>>()
    private val fullChartPointsByPeriod = ConcurrentHashMap<ListeningReportPeriod, List<ListeningChartPoint>>()
    private var cacheDirectory: File? = null

    private val localDeltas = CopyOnWriteArrayList<LocalPlaybackDelta>()
    private var todayBaselineFetchTimeMs: Long = 0L
    private var todayCloudBaselineReport: ListeningReportData? = null

    /**
     * 记录本地单次有效播放时长（>=5s），驱动今日统计乐观即时累加。
     */
    fun recordPlaybackDelta(
        song: Song,
        playedSec: Long,
    ) {
        if (playedSec < 5L) return
        val now = LocalDateTime.now(zoneUtc8)
        val todayStr = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val delta =
            LocalPlaybackDelta(
                songId = song.songId,
                playSec = playedSec,
                timestampMs = System.currentTimeMillis(),
                dateKey = todayStr,
                hour = now.hour,
            )
        localDeltas.add(delta)
        localDeltas.removeIf { it.dateKey != todayStr }

        applyOptimisticUpdateIfApplicable(todayStr)
    }

    private fun applyOptimisticUpdateIfApplicable(todayStr: String) {
        val currentPeriod = _selectedPeriod.value
        val currentDate = _selectedDate.value
        if (currentPeriod != ListeningReportPeriod.Day) return
        if (currentDate != null && currentDate != todayStr) return

        val baseline = todayCloudBaselineReport ?: _currentStats.value ?: return
        val uncommittedDeltas = localDeltas.filter { it.dateKey == todayStr && it.timestampMs > todayBaselineFetchTimeMs }
        if (uncommittedDeltas.isEmpty()) return

        val addedSec = uncommittedDeltas.sumOf { it.playSec }
        val addedCnt = uncommittedDeltas.size

        val updatedChartPoints = baseline.chartPoints.toMutableList()
        val deltaByHour = uncommittedDeltas.groupBy { it.hour }
        deltaByHour.forEach { (h, deltas) ->
            val hourSlot = h.toString()
            val hourAddedSec = deltas.sumOf { it.playSec }
            val idx = updatedChartPoints.indexOfFirst { it.slot == hourSlot }
            if (idx >= 0) {
                val pt = updatedChartPoints[idx]
                updatedChartPoints[idx] = pt.copy(value = pt.value + hourAddedSec)
            } else {
                updatedChartPoints.add(ListeningChartPoint(slot = hourSlot, value = hourAddedSec))
            }
        }
        val maxHourPt = updatedChartPoints.maxByOrNull { it.value }
        val newActiveHour = maxHourPt?.slot?.toIntOrNull() ?: baseline.activeHour

        val optimisticReport =
            baseline.copy(
                listenTimeSec = baseline.listenTimeSec + addedSec,
                listenCnt = baseline.listenCnt + addedCnt,
                activeHour = if (newActiveHour > 0) newActiveHour else baseline.activeHour,
                chartPoints = updatedChartPoints,
            )

        _currentStats.value = optimisticReport
        val cacheKey = "${ListeningReportPeriod.Day.typeCode}_${currentDate ?: "latest"}"
        cache[cacheKey] = optimisticReport
        cache["${ListeningReportPeriod.Day.typeCode}_$todayStr"] = optimisticReport
    }

    /**
     * 初始化持久化存储环境。
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        cacheDirectory = appContext.cacheDir.resolve("listening_stats_cache").apply { mkdirs() }
    }

    /**
     * 切换统计周期（日/周/月/年）。
     */
    suspend fun setPeriod(period: ListeningReportPeriod) {
        if (_selectedPeriod.value == period) return
        _selectedPeriod.value = period
        _selectedDate.value = null
        loadStats(period, null)
    }

    /**
     * 切换选定日期。
     */
    suspend fun setDate(date: String?) {
        _selectedDate.value = date
        loadStats(_selectedPeriod.value, date)
    }

    /**
     * 刷新当前维度的统计数据。
     */
    suspend fun refresh(force: Boolean = true) {
        loadStats(_selectedPeriod.value, _selectedDate.value, force = force)
    }

    private fun isHistoricalPeriod(
        period: ListeningReportPeriod,
        date: String?,
    ): Boolean {
        if (date.isNullOrBlank()) return false
        val now = LocalDate.now(zoneUtc8)
        return when (period) {
            ListeningReportPeriod.Day -> {
                val todayStr = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                date < todayStr
            }
            ListeningReportPeriod.Week -> {
                val currentMonday =
                    now.with(
                        java.time.temporal.TemporalAdjusters
                            .previousOrSame(java.time.DayOfWeek.MONDAY),
                    )
                val currentMondayStr = currentMonday.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                date < currentMondayStr
            }
            ListeningReportPeriod.Month -> {
                val currentMonthStr = now.format(DateTimeFormatter.ofPattern("yyyyMM"))
                date < currentMonthStr
            }
            ListeningReportPeriod.Year -> {
                val currentYearStr = now.year.toString()
                date < currentYearStr
            }
        }
    }

    private fun loadFromDisk(cacheKey: String): ListeningReportData? {
        val dir = cacheDirectory ?: return null
        val file = File(dir, "$cacheKey.json")
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            json.decodeFromString<ListeningReportData>(content)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveToDisk(
        cacheKey: String,
        report: ListeningReportData,
    ) {
        val dir = cacheDirectory ?: return
        try {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$cacheKey.json")
            val content = json.encodeToString(ListeningReportData.serializer(), report)
            file.writeText(content, Charsets.UTF_8)
        } catch (e: Exception) {
            ApiLogger.w("ListeningStatsManager", "Failed to cache listening report to disk: $cacheKey", e)
        }
    }

    private suspend fun loadStats(
        period: ListeningReportPeriod,
        date: String?,
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) {
            _currentStats.value = null
            return@withContext
        }

        val cacheKey = "${period.typeCode}_${date ?: "latest"}"
        val isHistorical = isHistoricalPeriod(period, date)

        if (!force) {
            val memoryCached = cache[cacheKey]
            if (memoryCached != null) {
                if (isHistorical) {
                    _currentStats.value = memoryCached
                    return@withContext
                } else {
                    val lastFetch = fetchTimeMap[cacheKey] ?: 0L
                    if (System.currentTimeMillis() - lastFetch < RECENT_DATA_TTL_MS) {
                        _currentStats.value = memoryCached
                        return@withContext
                    }
                }
            } else if (isHistorical) {
                val diskCached = loadFromDisk(cacheKey)
                if (diskCached != null) {
                    cache[cacheKey] = diskCached
                    _currentStats.value = diskCached
                    return@withContext
                }
            }
        }

        _isLoading.value = true
        try {
            val rawReport = apiService.getListeningRecordReport(period = period, date = date)

            val knownDates = fullAvailableDatesByPeriod[period].orEmpty()
            val mergedAvailableDates =
                (rawReport.availableDates + knownDates)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sortedDescending()
            if (mergedAvailableDates.isNotEmpty()) {
                fullAvailableDatesByPeriod[period] = mergedAvailableDates
            }

            val baselinePoints = fullChartPointsByPeriod[period].orEmpty()
            val currentPointsMap = rawReport.chartPoints.associateBy { it.slot }
            val baselinePointsMap = baselinePoints.associateBy { it.slot }
            val allSlots =
                (currentPointsMap.keys + baselinePointsMap.keys)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()

            val mergedPoints =
                if (allSlots.isNotEmpty()) {
                    allSlots.mapNotNull { slot ->
                        currentPointsMap[slot] ?: baselinePointsMap[slot]
                    }
                } else {
                    rawReport.chartPoints
                }

            if (mergedPoints.isNotEmpty()) {
                fullChartPointsByPeriod[period] = mergedPoints
            }

            val now = LocalDate.now(zoneUtc8)
            val todayStr = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            val isTodayDayView = period == ListeningReportPeriod.Day && (date == null || date == todayStr)

            val report =
                if (isTodayDayView) {
                    val fetchTime = System.currentTimeMillis()
                    todayBaselineFetchTimeMs = fetchTime
                    todayCloudBaselineReport = rawReport

                    // 过滤出在此次拉取后发生的未确认增量
                    val uncommittedDeltas = localDeltas.filter { it.dateKey == todayStr && it.timestampMs > fetchTime }
                    val uncommittedSec = uncommittedDeltas.sumOf { it.playSec }
                    val uncommittedCnt = uncommittedDeltas.size

                    // 避免云端未及时汇总导致数字倒退：保证时长与首数单调不减少
                    val previousSec = _currentStats.value?.takeIf { it.period == ListeningReportPeriod.Day && it.dateKey == todayStr }?.listenTimeSec ?: 0L
                    val finalSec = maxOf(rawReport.listenTimeSec + uncommittedSec, previousSec)
                    val previousCnt = _currentStats.value?.takeIf { it.period == ListeningReportPeriod.Day && it.dateKey == todayStr }?.listenCnt ?: 0
                    val finalCnt = maxOf(rawReport.listenCnt + uncommittedCnt, previousCnt)

                    rawReport.copy(
                        availableDates = if (mergedAvailableDates.isNotEmpty()) mergedAvailableDates else rawReport.availableDates,
                        chartPoints = if (mergedPoints.isNotEmpty()) mergedPoints else rawReport.chartPoints,
                        listenTimeSec = finalSec,
                        listenCnt = finalCnt,
                    )
                } else {
                    rawReport.copy(
                        availableDates = if (mergedAvailableDates.isNotEmpty()) mergedAvailableDates else rawReport.availableDates,
                        chartPoints = if (mergedPoints.isNotEmpty()) mergedPoints else rawReport.chartPoints,
                    )
                }

            cache[cacheKey] = report
            fetchTimeMap[cacheKey] = System.currentTimeMillis()
            if (isHistorical) {
                saveToDisk(cacheKey, report)
            }

            _currentStats.value = report
            if (date == null && report.dateKey.isNotBlank()) {
                _selectedDate.value = report.dateKey
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w("ListeningStatsManager", "Failed to load listening stats: period=$period, date=$date", e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * 清理内存与本地持久化缓存。
     */
    fun clearCache() {
        cache.clear()
        fetchTimeMap.clear()
        fullAvailableDatesByPeriod.clear()
        fullChartPointsByPeriod.clear()
        localDeltas.clear()
        todayBaselineFetchTimeMs = 0L
        todayCloudBaselineReport = null
        _currentStats.value = null
        try {
            cacheDirectory?.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            ApiLogger.w("ListeningStatsManager", "Failed to clear disk cache", e)
        }
    }
}
