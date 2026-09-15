package org.melodist.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioQualityFormatterTest {
    @Test
    fun `formatTrackQuality returns tier badge when specs are 0`() {
        assertEquals("SQ", AudioQualityFormatter.formatTrackQuality(AudioQualityTier.SQ))
        assertEquals("Hi-Res", AudioQualityFormatter.formatTrackQuality(AudioQualityTier.HiRes))
        assertEquals("母带", AudioQualityFormatter.formatTrackQuality(AudioQualityTier.Master))
        assertEquals("HQ", AudioQualityFormatter.formatTrackQuality(AudioQualityTier.HQ))
        assertEquals("标准", AudioQualityFormatter.formatTrackQuality(AudioQualityTier.Standard))
        assertEquals("SQ", AudioQualityFormatter.formatTrackQuality(null))
    }

    @Test
    fun `formatTrackQuality formats full specs correctly`() {
        val result =
            AudioQualityFormatter.formatTrackQuality(
                tier = AudioQualityTier.HiRes,
                sampleRateHz = 96000,
                bitDepth = 24,
                bitrateKbps = 2800,
            )
        assertEquals("Hi-Res · 24-bit / 96kHz / 2800kbps", result)
    }

    @Test
    fun `formatTrackQuality formats fractional kHz correctly`() {
        val result =
            AudioQualityFormatter.formatTrackQuality(
                tier = AudioQualityTier.SQ,
                sampleRateHz = 44100,
                bitDepth = 16,
            )
        assertEquals("SQ · 16-bit / 44.1kHz", result)
    }

    @Test
    fun `formatDuration formats minutes and seconds correctly`() {
        assertEquals("0:00", AudioQualityFormatter.formatDuration(0))
        assertEquals("0:00", AudioQualityFormatter.formatDuration(-10))
        assertEquals("0:05", AudioQualityFormatter.formatDuration(5))
        assertEquals("0:59", AudioQualityFormatter.formatDuration(59))
        assertEquals("1:00", AudioQualityFormatter.formatDuration(60))
        assertEquals("3:45", AudioQualityFormatter.formatDuration(225))
        assertEquals("10:02", AudioQualityFormatter.formatDuration(602))
    }

    @Test
    fun `formatDurationMs converts milliseconds to formatted duration`() {
        assertEquals("0:00", AudioQualityFormatter.formatDurationMs(0L))
        assertEquals("3:45", AudioQualityFormatter.formatDurationMs(225000L))
        assertEquals("3:45", AudioQualityFormatter.formatDurationMs(225890L))
    }

    @Test
    fun `formatFileSize formats B, KB, MB, GB accurately`() {
        assertEquals("0 B", AudioQualityFormatter.formatFileSize(0))
        assertEquals("0 B", AudioQualityFormatter.formatFileSize(-50))
        assertEquals("500 B", AudioQualityFormatter.formatFileSize(500))
        assertEquals("1.0 KB", AudioQualityFormatter.formatFileSize(1024))
        assertEquals("2.5 KB", AudioQualityFormatter.formatFileSize(2560))
        assertEquals("10.0 MB", AudioQualityFormatter.formatFileSize(10 * 1024 * 1024))
        assertEquals("1.50 GB", AudioQualityFormatter.formatFileSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }
}
