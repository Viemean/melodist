package org.melodist.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.melodist.model.ListeningChartPoint
import org.melodist.model.ListeningReportData
import org.melodist.model.ListeningReportPeriod
import org.melodist.model.Song
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ListeningStatsManagerDeltaTest {
    @BeforeEach
    fun setup() {
        ListeningStatsManager.clearCache()
    }

    @Test
    fun testRecordPlaybackDeltaIgnoresLessThan5Seconds() {
        val song = Song(songId = 100L, songMid = "mid_100", name = "Test Song")
        ListeningStatsManager.recordPlaybackDelta(song, 4L)
    }

    @Test
    fun testRecordPlaybackDeltaAppliesOptimisticUpdateToTodayReport() {
        val zoneUtc8 = ZoneId.of("GMT+8")
        val todayStr = LocalDate.now(zoneUtc8).format(DateTimeFormatter.ofPattern("yyyyMMdd"))

        val baseline =
            ListeningReportData(
                period = ListeningReportPeriod.Day,
                dateKey = todayStr,
                listenTimeSec = 1000L,
                listenCnt = 5,
                activeHour = 14,
                chartPoints = listOf(ListeningChartPoint(slot = "14", value = 1000L)),
                availableDates = listOf(todayStr),
            )

        val currentStatsField = ListeningStatsManager::class.java.getDeclaredField("_currentStats")
        currentStatsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = currentStatsField.get(ListeningStatsManager) as kotlinx.coroutines.flow.MutableStateFlow<ListeningReportData?>
        flow.value = baseline

        val baselineReportField = ListeningStatsManager::class.java.getDeclaredField("todayCloudBaselineReport")
        baselineReportField.isAccessible = true
        baselineReportField.set(ListeningStatsManager, baseline)

        val song = Song(songId = 101L, songMid = "mid_101", name = "晴天")
        ListeningStatsManager.recordPlaybackDelta(song, 120L)

        val updated = ListeningStatsManager.currentStats.value
        assertNotNull(updated)
        assertEquals(1120L, updated?.listenTimeSec)
        assertEquals(6, updated?.listenCnt)
    }
}
