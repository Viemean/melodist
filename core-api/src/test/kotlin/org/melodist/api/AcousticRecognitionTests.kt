package org.melodist.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.melodist.api.acr.AcousticFingerprintExtractor
import org.melodist.api.acr.AcousticRecognizeClient
import kotlin.math.sin

class AcousticRecognitionTests {
    @Test
    fun testDownsample16kTo8k() {
        val pcm16k = ShortArray(16000) { 1000 }
        val pcm8k = AcousticFingerprintExtractor.downsample16kTo8k(pcm16k)
        assertEquals(8000, pcm8k.size)
        assertEquals(1000, pcm8k[0].toInt())
    }

    @Test
    fun testExtractShortSamplesReturnsNull() {
        assertNull(AcousticFingerprintExtractor.extract(ShortArray(0)))
        assertNull(AcousticFingerprintExtractor.extract(ShortArray(1000)))
    }

    @Test
    fun testExtractSyntheticPcmProducesValidFeature() {
        // 生成 3 秒的合成音频信号 (包含 440Hz, 880Hz, 1200Hz 正弦波混合)
        val sampleRate = 8000
        val durationSec = 3
        val totalSamples = sampleRate * durationSec
        val pcm8k = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val s1 = sin(2.0 * Math.PI * 440.0 * t)
            val s2 = sin(2.0 * Math.PI * 880.0 * t)
            val s3 = sin(2.0 * Math.PI * 1200.0 * t)
            val sampleVal = ((s1 + s2 + s3) / 3.0 * 25000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm8k[i] = sampleVal.toShort()
        }

        val feature = AcousticFingerprintExtractor.extract(pcm8k)
        assertNotNull(feature)
        feature?.let {
            assertTrue(it.data.isNotEmpty())
            assertEquals(3.0f, it.duration, 0.01f)
            assertTrue(it.data.size >= 16)
        }
    }

    @Test
    fun testParseMockResponse() {
        val client = AcousticRecognizeClient()
        val mockJson =
            """
            {
                "code": 0,
                "ret": 0,
                "results": [
                    {
                        "offset": 12.345
                    }
                ],
                "songlist": [
                    {
                        "id": 10001,
                        "mid": "003mQIjO4e38e6",
                        "name": "夜曲",
                        "singer": [
                            {
                                "id": 4558,
                                "mid": "0025NhlN2yWrP4",
                                "name": "周杰伦"
                            }
                        ],
                        "album": {
                            "id": 8220,
                            "mid": "000IEl4n2pnD5k",
                            "name": "十一月的萧邦"
                        },
                        "interval": 226,
                        "file": {
                            "media_mid": "003mQIjO4e38e6"
                        }
                    }
                ]
            }
            """.trimIndent()

        val result = client.parseResponse(mockJson)
        assertTrue(result.success)
        assertEquals("夜曲", result.title)
        assertEquals("周杰伦", result.artist)
        assertEquals("十一月的萧邦", result.album)
        assertEquals(12.345, result.offsetSeconds, 0.001)
        assertNotNull(result.song)
        assertEquals("003mQIjO4e38e6", result.song?.songMid)
    }

    @Test
    fun testLiveSyncOffsetCalculation() {
        val recognizedOffset = 25.4
        val recognizedTimestampMs = 10000L
        val currentTimestampMs = 12500L
        val latencyCompSec = 0.35

        val elapsedSec = (currentTimestampMs - recognizedTimestampMs) / 1000.0
        val targetPlaybackPosSec = recognizedOffset + elapsedSec - latencyCompSec

        assertEquals(27.55, targetPlaybackPosSec, 0.001)
    }
}
