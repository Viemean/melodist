package org.melodist.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.melodist.model.SongDiaryData
import org.melodist.model.SongListenRecordItem
import org.melodist.model.SongListenRecordsPage

class SongDiaryCacheManagerTest {
    @BeforeEach
    fun setUp() {
        SongDiaryCacheManager.clear()
        SongDiaryCacheManager.timeProvider = { 1000L }
    }

    @Test
    fun testDiaryCacheHitAndExpiration() {
        var currentTime = 1000L
        SongDiaryCacheManager.timeProvider = { currentTime }

        val dummyDiary = SongDiaryData(
            songMid = "test_mid",
            title = "Test Title",
        )

        SongDiaryCacheManager.diaryCache.put("test_mid", dummyDiary)
        assertEquals(dummyDiary, SongDiaryCacheManager.diaryCache.get("test_mid"))

        // 59 秒后仍在有效期内
        currentTime += 59_000L
        assertEquals(dummyDiary, SongDiaryCacheManager.diaryCache.get("test_mid"))

        // 超过 60 秒后失效
        currentTime += 2_000L
        assertNull(SongDiaryCacheManager.diaryCache.get("test_mid"))
    }

    @Test
    fun testRecordsCacheHitAndExpiration() {
        var currentTime = 1000L
        SongDiaryCacheManager.timeProvider = { currentTime }

        val dummyPage = SongListenRecordsPage(
            items = listOf(SongListenRecordItem(timestampSec = 1700000000L, device = "Phone")),
            nextCursor = "cursor_2",
        )

        val key = "test_mid:cursor_1"
        SongDiaryCacheManager.recordsCache.put(key, dummyPage)
        assertEquals(dummyPage, SongDiaryCacheManager.recordsCache.get(key))

        // 61 秒后失效
        currentTime += 61_000L
        assertNull(SongDiaryCacheManager.recordsCache.get(key))
    }

    @Test
    fun testClearRemovesAllEntries() {
        val dummyDiary = SongDiaryData(songMid = "mid_1", title = "T1")
        val dummyPage = SongListenRecordsPage(emptyList(), "0")

        SongDiaryCacheManager.diaryCache.put("mid_1", dummyDiary)
        SongDiaryCacheManager.recordsCache.put("mid_1:", dummyPage)

        assertEquals(1, SongDiaryCacheManager.diaryCache.size)
        assertEquals(1, SongDiaryCacheManager.recordsCache.size)

        SongDiaryCacheManager.clear()

        assertEquals(0, SongDiaryCacheManager.diaryCache.size)
        assertEquals(0, SongDiaryCacheManager.recordsCache.size)
    }
}
