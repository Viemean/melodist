package org.melodist.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CacheUsageDetailTest {
    @Test
    fun `mediaUsageFraction calculates ratio clamped between 0 and 1`() {
        val zeroQuota = CacheUsageDetail(mediaCacheBytes = 100L, mediaQuotaBytes = 0L)
        assertEquals(0f, zeroQuota.mediaUsageFraction)

        val halfUsage =
            CacheUsageDetail(
                mediaCacheBytes = 1024L * 1024L * 1024L,
                mediaQuotaBytes = 2L * 1024L * 1024L * 1024L,
            )
        assertEquals(0.5f, halfUsage.mediaUsageFraction, 0.001f)

        val overflowUsage =
            CacheUsageDetail(
                mediaCacheBytes = 3L * 1024L * 1024L * 1024L,
                mediaQuotaBytes = 2L * 1024L * 1024L * 1024L,
            )
        assertEquals(1.0f, overflowUsage.mediaUsageFraction)
    }

    @Test
    fun `formatBytes formats quota correctly`() {
        val detail =
            CacheUsageDetail(
                mediaCacheBytes = 500L * 1024L * 1024L,
                mediaQuotaBytes = 2L * 1024L * 1024L * 1024L,
                cachedTrackCount = 42,
            )
        assertTrue(detail.mediaQuotaFormatted.contains("2048.0 MB") || detail.mediaQuotaFormatted.contains("2048 MB"))
        assertEquals(42, detail.cachedTrackCount)
    }
}
