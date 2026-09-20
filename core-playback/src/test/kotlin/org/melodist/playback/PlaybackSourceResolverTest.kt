package org.melodist.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

class PlaybackSourceResolverTest {
    @Test
    fun `isLocalOrWebDavSong identifies WebDav, Local, and file path songs`() {
        assertFalse(PlaybackSourceResolver.isLocalOrWebDavSong(null))

        val onlineSong = Song(songId = 1, songMid = "003mQIjO4e38e6", name = "Test Online")
        assertFalse(PlaybackSourceResolver.isLocalOrWebDavSong(onlineSong))

        val webDavSong = Song(songId = 2, songMid = "webdav_abc123", name = "Test WebDAV")
        assertTrue(PlaybackSourceResolver.isLocalOrWebDavSong(webDavSong))

        val localPrefixSong = Song(songId = 3, songMid = "local_456", name = "Test Local Prefix")
        assertTrue(PlaybackSourceResolver.isLocalOrWebDavSong(localPrefixSong))

        val localFilePathSong =
            Song(
                songId = 4,
                songMid = "003mQIjO4e38e6",
                name = "Test Downloaded",
                localFilePath = "/storage/emulated/0/Music/test.flac",
            )
        assertTrue(PlaybackSourceResolver.isLocalOrWebDavSong(localFilePathSong))
    }

    @Test
    fun `getAudioQualityRank assigns correct hierarchical order`() {
        val masterRank = PlaybackSourceResolver.getAudioQualityRank(AudioQualityTier.Master)
        val hiResRank = PlaybackSourceResolver.getAudioQualityRank(AudioQualityTier.HiRes)
        val sqRank = PlaybackSourceResolver.getAudioQualityRank(AudioQualityTier.SQ)
        val hqRank = PlaybackSourceResolver.getAudioQualityRank(AudioQualityTier.HQ)
        val standardRank = PlaybackSourceResolver.getAudioQualityRank(AudioQualityTier.Standard)

        assertTrue(masterRank > hiResRank)
        assertTrue(hiResRank > sqRank)
        assertTrue(sqRank > hqRank)
        assertTrue(hqRank > standardRank)

        val atmosRank = PlaybackSourceResolver.getAudioQualityRank(AudioQualityTier.Atmos)
        val dolbyRank = PlaybackSourceResolver.getAudioQualityRank(AudioQualityTier.Dolby)
        val premiumRank = PlaybackSourceResolver.getAudioQualityRank(AudioQualityTier.Premium)

        assertEquals(atmosRank, dolbyRank)
        assertTrue(atmosRank > premiumRank)
    }

    @Test
    fun `getFallbackTier degrades quality step by step`() {
        assertEquals(AudioQualityTier.HiRes, PlaybackSourceResolver.getFallbackTier(AudioQualityTier.Master))
        assertEquals(AudioQualityTier.SQ, PlaybackSourceResolver.getFallbackTier(AudioQualityTier.HiRes))
        assertEquals(AudioQualityTier.HQ, PlaybackSourceResolver.getFallbackTier(AudioQualityTier.SQ))
        assertEquals(AudioQualityTier.Standard, PlaybackSourceResolver.getFallbackTier(AudioQualityTier.HQ))
        assertNull(PlaybackSourceResolver.getFallbackTier(AudioQualityTier.Standard))

        assertEquals(AudioQualityTier.Dolby, PlaybackSourceResolver.getFallbackTier(AudioQualityTier.Atmos))
        assertEquals(AudioQualityTier.SQ, PlaybackSourceResolver.getFallbackTier(AudioQualityTier.Dolby))
        assertEquals(AudioQualityTier.SQ, PlaybackSourceResolver.getFallbackTier(AudioQualityTier.Premium))
    }

    private class TestTvContext(private val pkg: String) : android.content.ContextWrapper(null) {
        override fun getPackageName(): String = pkg
    }

    @Test
    fun `isTelevision returns false for null context`() {
        assertFalse(PlaybackSourceResolver.isTelevision(null))
    }

    @Test
    fun `isTelevision identifies tv package`() {
        val tvContext = TestTvContext("org.melodist.tv")
        assertTrue(PlaybackSourceResolver.isTelevision(tvContext))

        val mobileContext = TestTvContext("org.melodist.mobile")
        assertFalse(PlaybackSourceResolver.isTelevision(mobileContext))
    }

    @Test
    fun `isCellularNetwork returns false for tv context`() {
        val tvContext = TestTvContext("org.melodist.tv")
        assertFalse(PlaybackSourceResolver.isCellularNetwork(tvContext))
    }

    @Test
    fun `clampCellularTier does not restrict quality on tv context`() {
        val tvContext = TestTvContext("org.melodist.tv")
        val onlineSong = Song(songId = 10, songMid = "003mQIjO4e38e6", name = "Test Online")

        // 即使请求 Master，移动网络限制为 HQ，在 TV 环境下也不应被限制
        val clamped = PlaybackSourceResolver.clampCellularTier(
            requestedTier = AudioQualityTier.Master,
            song = onlineSong,
            context = tvContext,
            cellularLimit = AudioQualityTier.HQ,
        )
        assertEquals(AudioQualityTier.Master, clamped)
    }

    @Test
    fun `shouldTriggerPrefetch accurately identifies timing window`() {
        // 短曲目（<= 20秒）不触发
        assertFalse(PlaybackSourceResolver.shouldTriggerPrefetch(durationMs = 20_000L, positionMs = 15_000L))
        assertFalse(PlaybackSourceResolver.shouldTriggerPrefetch(durationMs = 10_000L, positionMs = 9_000L))

        // 歌曲 200 秒：剩余 > 20s 且进度 < 85%（例如播放 100s，进度 50%）不触发
        assertFalse(PlaybackSourceResolver.shouldTriggerPrefetch(durationMs = 200_000L, positionMs = 100_000L))

        // 歌曲 200 秒：播放到 170 秒（85% 门槛，剩余 30s > 20s）触发
        assertTrue(PlaybackSourceResolver.shouldTriggerPrefetch(durationMs = 200_000L, positionMs = 170_000L))

        // 歌曲 60 秒：播放到 41 秒（剩余 19 秒 <= 20s，进度 68.3% < 85%）触发
        assertTrue(PlaybackSourceResolver.shouldTriggerPrefetch(durationMs = 60_000L, positionMs = 41_000L))

        // 歌曲 60 秒：播放到 35 秒（剩余 25s，进度 58.3%）不触发
        assertFalse(PlaybackSourceResolver.shouldTriggerPrefetch(durationMs = 60_000L, positionMs = 35_000L))
    }
}

