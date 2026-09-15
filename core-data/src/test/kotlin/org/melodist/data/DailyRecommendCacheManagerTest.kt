package org.melodist.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.Song
import java.util.Calendar

class DailyRecommendCacheManagerTest {
    @Test
    fun testCacheValidInSame0600Cycle() {
        val now = System.currentTimeMillis()
        val calNow = Calendar.getInstance().apply { timeInMillis = now }

        val cycleStart =
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 6)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (calNow.before(this)) {
                    add(Calendar.DAY_OF_MONTH, -1)
                }
            }

        val validData =
            DailyRecommendData(
                songs = listOf(Song(songMid = "test_mid", coverUrl = "https://y.qq.com/music/photo_new/T002R1200x1200M000test.jpg")),
                fetchTimestamp = cycleStart.timeInMillis + 1000L,
                accountUin = "123456",
            )
        assertTrue(DailyRecommendCacheManager.isCacheValidInCycle(validData, "123456"))

        val expiredData =
            DailyRecommendData(
                songs = listOf(Song(songMid = "test_mid")),
                fetchTimestamp = cycleStart.timeInMillis - 1000L,
                accountUin = "123456",
            )
        assertFalse(DailyRecommendCacheManager.isCacheValidInCycle(expiredData, "123456"))

        // 账号不匹配也应失效
        assertFalse(DailyRecommendCacheManager.isCacheValidInCycle(validData, "654321"))
    }

    @Test
    fun testThumbnailUrlsExtractedForPreload() {
        val songs =
            listOf(
                Song(coverUrl = "https://y.qq.com/music/photo_new/T002R1200x1200M000mid1.jpg"),
                Song(coverUrl = "https://y.gtimg.cn/music/photo_new/T002R800x800M000mid2.jpg"),
                Song(coverUrl = ""),
            )
        val urls = songs.map { it.thumbnailCoverUrl }.filter { it.isNotBlank() }.distinct()
        org.junit.jupiter.api.Assertions
            .assertEquals(2, urls.size)
        assertTrue(urls[0].contains("R500x500"))
        assertTrue(urls[1].contains("R500x500"))
    }
}
