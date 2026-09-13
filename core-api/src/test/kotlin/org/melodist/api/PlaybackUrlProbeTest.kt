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
        assertEquals("7.1", AudioQualityTier.getBadge(AudioQualityTier.Atmos71))
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
                else -> emptyList()
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
}
