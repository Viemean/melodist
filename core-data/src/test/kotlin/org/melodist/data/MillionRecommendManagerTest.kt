package org.melodist.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.api.MillionRecommendResult
import org.melodist.model.Song
import java.util.Calendar

class MillionRecommendManagerTest {
    @Test
    fun testCacheValidInSameDayCycle() {
        val now = System.currentTimeMillis()

        val cycleStart =
            Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+8")).apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

        val validData =
            MillionRecommendData(
                result =
                    MillionRecommendResult(
                        disstid = 211111L,
                        title = "百万收藏",
                        songs = listOf(Song(songMid = "test_mid", name = "Test Song")),
                    ),
                fetchTimestamp = cycleStart.timeInMillis + 1000L,
                accountUin = "123456",
            )
        assertTrue(MillionRecommendManager.isCacheValidInCycle(validData, "123456", currentTimeMs = now))

        val expiredData =
            MillionRecommendData(
                result =
                    MillionRecommendResult(
                        disstid = 211111L,
                        songs = listOf(Song(songMid = "test_mid")),
                    ),
                fetchTimestamp = cycleStart.timeInMillis - 1000L,
                accountUin = "123456",
            )
        assertFalse(MillionRecommendManager.isCacheValidInCycle(expiredData, "123456", currentTimeMs = now))

        // 账号不一致失效
        assertFalse(MillionRecommendManager.isCacheValidInCycle(validData, "654321", currentTimeMs = now))

        // 歌曲列表为空失效
        val emptySongsData = validData.copy(result = validData.result.copy(songs = emptyList()))
        assertFalse(MillionRecommendManager.isCacheValidInCycle(emptySongsData, "123456", currentTimeMs = now))
    }
}
