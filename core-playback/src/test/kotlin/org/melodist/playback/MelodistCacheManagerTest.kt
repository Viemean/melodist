package org.melodist.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MelodistCacheManagerTest {
    @Test
    fun `formatBytes formats various byte sizes correctly`() {
        assertEquals("0 B", MelodistCacheManager.formatBytes(0L))
        assertEquals("0 B", MelodistCacheManager.formatBytes(-100L))
        assertEquals("512 B", MelodistCacheManager.formatBytes(512L))
        assertEquals("1 KB", MelodistCacheManager.formatBytes(1024L))
        assertEquals("1.5 MB", MelodistCacheManager.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("256 MB", MelodistCacheManager.formatBytes(256L * 1024L * 1024L))
        assertEquals("1 GB", MelodistCacheManager.formatBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun `calculateAdaptiveCacheQuotaBytes clamps to safe boundaries`(
        @TempDir tempDir: File,
    ) {
        val calculated = MelodistCacheManager.calculateAdaptiveCacheQuotaBytes(tempDir)
        // 验证计算结果在安全边界内：介于 128MB 与 1GB 之间
        assertTrue(calculated >= MelodistCacheManager.MIN_PROTECT_QUOTA_BYTES)
        assertTrue(calculated <= MelodistCacheManager.DEFAULT_MAX_QUOTA_BYTES)
    }

    @Test
    fun `calculateAdaptiveCacheQuotaBytes gracefully handles nonexistent path`() {
        val nonExistent = File("/path/to/nonexistent/directory/test")
        val fallback = MelodistCacheManager.calculateAdaptiveCacheQuotaBytes(nonExistent)
        assertEquals(MelodistCacheManager.MIN_PROTECT_QUOTA_BYTES, fallback)
    }

    @Test
    fun `uninitialized cache manager returns safe defaults`() {
        assertEquals(0L, MelodistCacheManager.getCacheSizeBytes())
        assertEquals(0, MelodistCacheManager.getCachedKeyCount())
    }
}
