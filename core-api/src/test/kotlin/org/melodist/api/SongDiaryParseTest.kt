package org.melodist.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SongDiaryParseTest {
    @Test
    fun testParseSongDiaryAndRecords() {
        val jsonFile = File("../.tmp/experiments/song_diary/baseline_api_response.json")
        if (!jsonFile.exists()) return
        val jsonStr = jsonFile.readText()

        val diary = parseSongDiary(jsonStr, "002CzSQn20I2wy")
        assertNotNull(diary)
        assertEquals("002CzSQn20I2wy", diary?.songMid)
        assertEquals("Beautiful Tomorrow", diary?.title)
        assertEquals("2025.10.19", diary?.favDate)
        assertEquals("初秋的深夜", diary?.firstLsnTimeCn)
        assertEquals("2025.10.19 23:16", diary?.firstLsnTime)
        assertEquals("Android手机", diary?.firstLsnDevice)
        assertEquals(358, diary?.firstLsnDayCnt)
        assertEquals("32次", diary?.totalLsnCountText)
        assertEquals(6, diary?.totalLsnYears?.size)
        assertEquals(listOf(0, 0, 0, 0, 19, 13), diary?.totalLsnCounts)
        assertEquals("8次", diary?.recentLsnCountText)
        assertEquals(6, diary?.recentLsnMonths?.size)
        assertEquals("16:30", diary?.lastLsnTime)
        assertEquals("2026年10月12日", diary?.lastLsnDate)
        assertTrue(diary?.hasListenRecord == true)

        val records = parseSongListenRecords(jsonStr)
        assertNotNull(records)
        assertTrue((records?.items?.size ?: 0) > 0)
        assertEquals("Android手机", records?.items?.firstOrNull()?.device)
    }

    @Test
    fun testParseSongDiaryWithNullDates() {
        val jsonFile = File("../.tmp/experiments/song_diary/variant_null_dates_response.json")
        if (!jsonFile.exists()) return
        val jsonStr = jsonFile.readText()

        val diary = parseSongDiary(jsonStr, "003bBlVW4A6zPD")
        assertNotNull(diary)
        assertEquals("003bBlVW4A6zPD", diary?.songMid)
        assertEquals("私たちのあやまち", diary?.title)
        assertEquals("2026.10.10", diary?.favDate)
        assertEquals(355, diary?.firstLsnDayCnt)
        assertEquals(0, diary?.recentLsnMonths?.size)
        assertTrue(diary?.hasListenRecord == true)
    }
}
