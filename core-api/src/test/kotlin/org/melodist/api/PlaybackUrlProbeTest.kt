package org.melodist.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.melodist.model.AudioQualityTier
import org.melodist.model.QualityOption

class PlaybackUrlProbeTest {
    @Test
    fun `audioQualityTier getBadge returns correct UI labels`() {
        assertEquals("母带", AudioQualityTier.getBadge(AudioQualityTier.Master))
        assertEquals("Hi-Res", AudioQualityTier.getBadge(AudioQualityTier.HiRes))
        assertEquals("全景声", AudioQualityTier.getBadge(AudioQualityTier.Atmos))
        assertEquals("SQ", AudioQualityTier.getBadge(AudioQualityTier.SQ))
        assertEquals("HQ", AudioQualityTier.getBadge(AudioQualityTier.HQ))
        assertEquals("标准", AudioQualityTier.getBadge(AudioQualityTier.Standard))
    }

    @Test
    fun `qualityOption stores accurate media attributes`() {
        val option =
            QualityOption(
                tier = AudioQualityTier.HiRes,
                format = "FLAC",
                bitrate = "96kHz/24bit",
                sizeBytes = 45120000L,
                isAvailable = true,
                playUrl = "https://isure.stream.qqmusic.qq.com/RS01.flac",
            )

        assertTrue(option.isAvailable)
        assertEquals("FLAC", option.format)
        assertEquals("96kHz/24bit", option.bitrate)
        assertNotNull(option.playUrl)
        assertEquals(AudioQualityTier.HiRes, option.tier)
    }

    @Test
    fun `qualityResult preferred fallback decision order`() {
        // 场景：歌曲具备 Master 和 Standard，但用户选择的是 HiRes，应向下寻找降级而非向上逆跳到 Master
        val availableTiers =
            mapOf(
                AudioQualityTier.Master to "https://isure.stream.qqmusic.qq.com/AI00.flac",
                AudioQualityTier.Standard to "https://isure.stream.qqmusic.qq.com/M500.mp3",
            )

        val preferredTier = AudioQualityTier.HiRes

        val fallbackCandidates =
            when (preferredTier) {
                AudioQualityTier.Master ->
                    listOf(
                        AudioQualityTier.Atmos,
                        AudioQualityTier.Dolby,
                        AudioQualityTier.Premium,
                        AudioQualityTier.HiRes,
                        AudioQualityTier.SQ,
                        AudioQualityTier.HQ,
                        AudioQualityTier.Standard,
                    )
                AudioQualityTier.Atmos ->
                    listOf(
                        AudioQualityTier.Dolby,
                        AudioQualityTier.Premium,
                        AudioQualityTier.HiRes,
                        AudioQualityTier.SQ,
                        AudioQualityTier.HQ,
                        AudioQualityTier.Standard,
                    )
                AudioQualityTier.Dolby ->
                    listOf(
                        AudioQualityTier.Premium,
                        AudioQualityTier.HiRes,
                        AudioQualityTier.SQ,
                        AudioQualityTier.HQ,
                        AudioQualityTier.Standard,
                    )
                AudioQualityTier.Premium ->
                    listOf(
                        AudioQualityTier.HiRes,
                        AudioQualityTier.SQ,
                        AudioQualityTier.HQ,
                        AudioQualityTier.Standard,
                    )
                AudioQualityTier.HiRes ->
                    listOf(
                        AudioQualityTier.SQ,
                        AudioQualityTier.HQ,
                        AudioQualityTier.Standard,
                    )
                AudioQualityTier.SQ ->
                    listOf(
                        AudioQualityTier.HQ,
                        AudioQualityTier.Standard,
                    )
                AudioQualityTier.HQ ->
                    listOf(
                        AudioQualityTier.Standard,
                    )
                AudioQualityTier.Standard -> emptyList()
            }

        var selectedUrl: String? = null
        var selectedTier = AudioQualityTier.Standard

        for (tier in fallbackCandidates) {
            if (availableTiers.containsKey(tier)) {
                selectedUrl = availableTiers[tier]
                selectedTier = tier
                break
            }
        }

        assertNotNull(selectedUrl)
        // 向下级音质 Standard 回退
        assertEquals(AudioQualityTier.Standard, selectedTier)
        assertTrue(selectedUrl!!.endsWith(".mp3"))
    }

    @Test
    fun `hires option is marked unavailable when track metadata indicates standard resolution`() {
        val hiresRaw = 0L
        val hiresSample = 44100
        val hiresBitdepth = 16
        val isTrueHiRes = hiresRaw > 0L || hiresSample > 48000 || hiresBitdepth > 16

        val flacSize = 25000000L
        val sizeMap = mutableMapOf<AudioQualityTier, Long>()
        sizeMap[AudioQualityTier.HiRes] =
            if (isTrueHiRes) {
                if (hiresRaw > 0L) hiresRaw else flacSize
            } else {
                0L
            }
        sizeMap[AudioQualityTier.SQ] = flacSize

        val hasValidUrl = true
        val isAvailable = (sizeMap[AudioQualityTier.HiRes] ?: 0L) > 0L && hasValidUrl
        assertFalse(isAvailable)
    }

    @Test
    fun `hires option is marked unavailable for Dazzling style track with master but no hires`() {
        // Dazzling 明透: has Master AI (135MB) in size_new[0], but size_new[11] is 0, hires_sample is 0
        val hiresRaw = 0L
        val masterSize = 135232956L
        val hiresSample = 0
        val hiresBitdepth = 0
        val isTrueHiRes = hiresRaw > 0L || hiresSample > 48000 || hiresBitdepth > 16

        val flacSize = 22772836L
        val sizeMap = mutableMapOf<AudioQualityTier, Long>()
        sizeMap[AudioQualityTier.HiRes] =
            if (isTrueHiRes) {
                if (hiresRaw > 0L) hiresRaw else flacSize
            } else {
                0L
            }
        sizeMap[AudioQualityTier.Master] = masterSize
        sizeMap[AudioQualityTier.SQ] = flacSize

        val hasValidUrl = true
        val isHiResAvailable = (sizeMap[AudioQualityTier.HiRes] ?: 0L) > 0L && hasValidUrl
        val isMasterAvailable = (sizeMap[AudioQualityTier.Master] ?: 0L) > 0L && hasValidUrl

        // HiRes 必须为 false（无 HiRes 音源），Master 为 true
        assertFalse(isHiResAvailable)
        assertTrue(isMasterAvailable)
        assertEquals(0L, sizeMap[AudioQualityTier.HiRes])
        assertEquals(135232956L, sizeMap[AudioQualityTier.Master])
    }

    @Test
    fun `hires option is marked available when track has genuine hires size in size_hires`() {
        // 妄想感傷代償連盟: size_hires = 100164810, hires_sample = 96000, hires_bitdepth = 24
        val hiresRaw = 100164810L
        val masterSize = 198166746L
        val hiresSample = 96000
        val hiresBitdepth = 24
        val isTrueHiRes = hiresRaw > 0L || hiresSample > 48000 || hiresBitdepth > 16

        val flacSize = 61839633L
        val sizeMap = mutableMapOf<AudioQualityTier, Long>()
        sizeMap[AudioQualityTier.HiRes] =
            if (isTrueHiRes) {
                if (hiresRaw > 0L) hiresRaw else flacSize
            } else {
                0L
            }
        sizeMap[AudioQualityTier.Master] = masterSize
        sizeMap[AudioQualityTier.SQ] = flacSize

        val hasValidUrl = true
        val isHiResAvailable = (sizeMap[AudioQualityTier.HiRes] ?: 0L) > 0L && hasValidUrl
        val isMasterAvailable = (sizeMap[AudioQualityTier.Master] ?: 0L) > 0L && hasValidUrl

        assertTrue(isHiResAvailable)
        assertTrue(isMasterAvailable)
        assertEquals(100164810L, sizeMap[AudioQualityTier.HiRes])
        assertEquals(198166746L, sizeMap[AudioQualityTier.Master])
    }

    @Test
    fun `probe meteor fallback search lyrics`() =
        kotlinx.coroutines.runBlocking {
            val api = MusicApiService()
            val songs = api.search("メテオ ヰ世界情緒")
            val targetSong = songs.firstOrNull { it.name.contains("メテオ") }
            assertNotNull(targetSong)

            val lyrics = api.getLyrics(targetSong!!.songMid, targetSong.songId, songName = targetSong.name, singer = targetSong.singer)
            assertTrue(lyrics.isNotEmpty())
            println("Resolved lyrics count for ${targetSong.name}: ${lyrics.size}")
            println("First line: ${lyrics.first().text}, trans=${lyrics.first().transText}")
        }
}
