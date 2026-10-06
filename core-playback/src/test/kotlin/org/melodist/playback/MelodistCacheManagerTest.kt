package org.melodist.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertEquals("2 GB", MelodistCacheManager.formatBytes(2048L * 1024L * 1024L))
    }

    @Test
    fun `calculateAdaptiveCacheQuotaBytes clamps to safe boundaries`(
        @TempDir tempDir: File,
    ) {
        val calculated = MelodistCacheManager.calculateAdaptiveCacheQuotaBytes(tempDir)
        // 验证计算结果在安全边界内：介于 128MB 与 2GB 之间
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
    fun `smart admission rules distinguish favorite vs ordinary songs`() {
        val songMid = "test_song_mid_001"
        MelodistCacheManager.onNewSongStarted(songMid)

        // 初始状态：0次有效播放
        // 收藏曲目需要 >= 1 次，普通曲目需要 >= 2 次
        assertFalse(MelodistCacheManager.shouldCacheSong(songMid, isFavorite = false))
        assertFalse(MelodistCacheManager.shouldCacheSong(songMid, isFavorite = true))

        // 模拟播放未满 80% (例如 10s / 200s = 5%)
        MelodistCacheManager.recordPlayProgress(songMid, positionMs = 10_000L, durationMs = 200_000L)
        assertEquals(0, MelodistCacheManager.getPlayCount(songMid))

        // 模拟播放达到 85% (170s / 200s = 85%) -> 计为第 1 次有效播放
        MelodistCacheManager.recordPlayProgress(songMid, positionMs = 170_000L, durationMs = 200_000L)
        assertEquals(1, MelodistCacheManager.getPlayCount(songMid))

        // 同一次会话中重复触发不重复加计数
        MelodistCacheManager.recordPlayProgress(songMid, positionMs = 180_000L, durationMs = 200_000L)
        assertEquals(1, MelodistCacheManager.getPlayCount(songMid))

        // 达到 1 次：收藏曲目准入成功，普通曲目仍不准入
        assertTrue(MelodistCacheManager.shouldCacheSong(songMid, isFavorite = true))
        assertFalse(MelodistCacheManager.shouldCacheSong(songMid, isFavorite = false))

        // 开启新一轮播放会话，并再次播完 80%
        MelodistCacheManager.onNewSongStarted(songMid)
        MelodistCacheManager.recordPlayProgress(songMid, positionMs = 165_000L, durationMs = 200_000L)
        assertEquals(2, MelodistCacheManager.getPlayCount(songMid))

        // 达到 2 次：普通曲目也准入成功
        assertTrue(MelodistCacheManager.shouldCacheSong(songMid, isFavorite = true))
        assertTrue(MelodistCacheManager.shouldCacheSong(songMid, isFavorite = false))
    }

    @Test
    fun `playback profile calculates trial thresholds and adheres to quotas`() {
        // TV 试探期：min(15s, 20%)
        val tv = PlaybackProfile.TV
        assertEquals(2L * 1024 * 1024 * 1024, tv.maxCacheQuotaBytes)
        assertFalse(tv.allowMasterDiskCache)
        // 歌曲 200s: 200 * 20% = 40s > 15s -> 15s
        assertEquals(15_000L, tv.getTrialThresholdMs(200_000L))
        // 歌曲 50s: 50 * 20% = 10s < 15s -> 10s
        assertEquals(10_000L, tv.getTrialThresholdMs(50_000L))

        // Mobile 试探期：min(10s, 15%)
        val mobile = PlaybackProfile.Mobile
        assertEquals(6L * 1024 * 1024 * 1024, mobile.maxCacheQuotaBytes)
        assertTrue(mobile.allowMasterDiskCache)
        // 歌曲 200s: 200 * 15% = 30s > 10s -> 10s
        assertEquals(10_000L, mobile.getTrialThresholdMs(200_000L))
        // 歌曲 50s: 50 * 15% = 7.5s < 10s -> 7500ms
        assertEquals(7_500L, mobile.getTrialThresholdMs(50_000L))
    }

    @Test
    fun `getCacheKey generates normalized format`() {
        assertEquals("melodist_001_HQ", MelodistCacheManager.getCacheKey("001", org.melodist.model.AudioQualityTier.HQ))
        assertEquals("melodist_001_HiRes", MelodistCacheManager.getCacheKey("001", org.melodist.model.AudioQualityTier.HiRes))
        assertEquals("melodist_001", MelodistCacheManager.getCacheKey("001", null))
    }

    @Test
    fun `master tier is strictly stream-only when allowMasterDiskCache is false`() {
        val songMid = "test_master_song"
        MelodistCacheManager.onNewSongStarted(songMid)
        MelodistCacheManager.recordPlayProgress(songMid, positionMs = 180_000L, durationMs = 200_000L)
        MelodistCacheManager.onNewSongStarted(songMid)
        MelodistCacheManager.recordPlayProgress(songMid, positionMs = 180_000L, durationMs = 200_000L)
        assertEquals(2, MelodistCacheManager.getPlayCount(songMid))

        // 切换为 TV Profile（禁止 Master 落盘）
        MelodistCacheManager.currentProfile = PlaybackProfile.TV
        assertFalse(MelodistCacheManager.shouldCacheSong(songMid, isFavorite = true, tier = org.melodist.model.AudioQualityTier.Master))
        // 非 Master 音质在 TV 端准入成功
        assertTrue(MelodistCacheManager.shouldCacheSong(songMid, isFavorite = true, tier = org.melodist.model.AudioQualityTier.HiRes))

        // 切换为 Mobile Profile（允许 Master 落盘）
        MelodistCacheManager.currentProfile = PlaybackProfile.Mobile
        assertTrue(MelodistCacheManager.shouldCacheSong(songMid, isFavorite = true, tier = org.melodist.model.AudioQualityTier.Master))
    }

    @Test
    fun `stereo rank ordering follows physical audio quality hierarchy`() {
        val stdRank =
            org.melodist.model.AudioQualityTier
                .getStereoRank(org.melodist.model.AudioQualityTier.Standard)
        val hqRank =
            org.melodist.model.AudioQualityTier
                .getStereoRank(org.melodist.model.AudioQualityTier.HQ)
        val sqRank =
            org.melodist.model.AudioQualityTier
                .getStereoRank(org.melodist.model.AudioQualityTier.SQ)
        val hiResRank =
            org.melodist.model.AudioQualityTier
                .getStereoRank(org.melodist.model.AudioQualityTier.HiRes)
        val masterRank =
            org.melodist.model.AudioQualityTier
                .getStereoRank(org.melodist.model.AudioQualityTier.Master)
        val atmosRank =
            org.melodist.model.AudioQualityTier
                .getStereoRank(org.melodist.model.AudioQualityTier.Atmos)

        assertTrue(stdRank in 1..<hqRank)
        assertTrue(hqRank < sqRank)
        assertTrue(sqRank < hiResRank)
        assertTrue(hiResRank < masterRank)
        assertEquals(0, atmosRank) // 非立体声轨道不参与立体声收敛
    }

    @Test
    fun `uninitialized cache manager returns safe defaults`() {
        assertEquals(0L, MelodistCacheManager.getCacheSizeBytes())
        assertEquals(0, MelodistCacheManager.getCachedKeyCount())
        assertFalse(MelodistCacheManager.isKeyCached("test_key"))
        assertFalse(MelodistCacheManager.isKeyFullyCached("test_key"))
        assertFalse(MelodistCacheManager.isSongTierCached("test_mid", org.melodist.model.AudioQualityTier.SQ))
        assertFalse(MelodistCacheManager.isSongTierFullyCached("test_mid", org.melodist.model.AudioQualityTier.SQ))
        assertFalse(MelodistCacheManager.isTierFullyCached("test_mid", org.melodist.model.AudioQualityTier.SQ))
        assertFalse(MelodistCacheManager.isUriCached("https://example.com/audio.flac"))
    }

    @Test
    fun `detectAudioExtension identifies flac magic bytes`(
        @TempDir tempDir: File,
    ) {
        val file =
            File(tempDir, "sample.flac").apply {
                writeBytes(byteArrayOf(0x66, 0x4C, 0x61, 0x43) + ByteArray(32))
            }
        assertEquals("flac", MelodistCacheManager.detectAudioExtension(file))
    }

    @Test
    fun `detectAudioExtension identifies mp3 with id3 tag`(
        @TempDir tempDir: File,
    ) {
        val file =
            File(tempDir, "sample.mp3").apply {
                writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00) + ByteArray(32))
            }
        assertEquals("mp3", MelodistCacheManager.detectAudioExtension(file))
    }

    @Test
    fun `detectAudioExtension identifies mp3 raw frame sync`(
        @TempDir tempDir: File,
    ) {
        val file =
            File(tempDir, "sample_raw.mp3").apply {
                writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()) + ByteArray(32))
            }
        assertEquals("mp3", MelodistCacheManager.detectAudioExtension(file))
    }

    @Test
    fun `detectAudioExtension identifies m4a container`(
        @TempDir tempDir: File,
    ) {
        val file =
            File(tempDir, "sample.m4a").apply {
                writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70) + ByteArray(32))
            }
        assertEquals("m4a", MelodistCacheManager.detectAudioExtension(file))
    }

    @Test
    fun `detectAudioExtension identifies wav riff container`(
        @TempDir tempDir: File,
    ) {
        val file =
            File(tempDir, "sample.wav").apply {
                writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45) + ByteArray(32))
            }
        assertEquals("wav", MelodistCacheManager.detectAudioExtension(file))
    }

    @Test
    fun `detectAudioExtension identifies ogg container`(
        @TempDir tempDir: File,
    ) {
        val file =
            File(tempDir, "sample.ogg").apply {
                writeBytes(byteArrayOf(0x4F, 0x67, 0x67, 0x53) + ByteArray(32))
            }
        assertEquals("ogg", MelodistCacheManager.detectAudioExtension(file))
    }

    @Test
    fun `detectAudioExtension rejects invalid or truncated headers`(
        @TempDir tempDir: File,
    ) {
        val htmlFile =
            File(tempDir, "error.html").apply {
                writeText("<html><body>404 Not Found</body></html>")
            }
        assertEquals(null, MelodistCacheManager.detectAudioExtension(htmlFile))

        val shortFile =
            File(tempDir, "short.bin").apply {
                writeBytes(byteArrayOf(0x01, 0x02))
            }
        assertEquals(null, MelodistCacheManager.detectAudioExtension(shortFile))
    }

    @Test
    fun `exportCompleteCachedSong returns null when uninitialized or key missing`(
        @TempDir tempDir: File,
    ) {
        val result =
            MelodistCacheManager.exportCompleteCachedSong(
                songMid = "non_existent_mid",
                tier = org.melodist.model.AudioQualityTier.SQ,
                targetDir = tempDir,
                baseName = "test_song",
            )
        assertEquals(null, result)
    }

    @Test
    fun `isKeyFullyCached returns false for blank key or uninitialized cache`() {
        assertFalse(MelodistCacheManager.isKeyFullyCached(null))
        assertFalse(MelodistCacheManager.isKeyFullyCached(""))
        assertFalse(MelodistCacheManager.isKeyFullyCached("non_existent_key"))
    }
}
